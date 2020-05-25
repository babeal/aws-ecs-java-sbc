# AWS ECS container parameters effect on metrics and scaling

Over the last few months I've had to support a ECS solution running Java 8 containers.  The original setup took micro-service principals to the nth degree and each service had it's own ECS cluster which was horribly inefficient so I setout to change it.  I consolidated all of the containers to run on a single ECS cluster and added scaling to both the containers and underlying EC2 instances.  The scaling policies weren't straightforward leading to lots of trial and error and it wasn't until I started getting 502's and 503's did I decide to make this project and really dive deep into the behavior of ECS, Scaling policies and CloudWatch metrics.  

Goals:

Find a setup that remains ultra efficient for development deployments but can scale to small production deployments if needed.

- development - services are mostly dormant and when the system is in use only a small minority of services are busy at any one time
- production - workload spread across the services

So lets dive in...

This solution contains a Java 8 Spring Boot application that has methods for adding cpu and memory load.  

Controller methods

- `/java` - will return java stats about the app i.e. `Java version: 1.8.0_252; Procs: 1, Memory (used,total,max): 18mb / 61mb / 989mb`
- `/add/cpu/{time_in_seconds}/{threads}` - add cpu load for a period of time.  How much load is dependent on the cpu architecture and efficiency.  On my machine, each 100 threads is ~10% cpu.  
- `/add/memory/{time_in_seconds}/{amount_in_mb}` - consumes memory for a period of time
- `/forcegc` - forces a garbage collection to see the effect after releasing memory
- `/liveness_check` - a liveness check for the load balancer health check

Also in the java solution under the directory src/main/resources is an application.properties file.  This file exists to shows the ability to retrieve values from the environment but inability to set java related properties like memory.

The `/deploy` folder contains CloudFormation stack templates for the ECS cluster, service and service auto-scaling.

## How to build and run the container locally

Build the container:

```
docker build -t ecs-perf .
```

Run the container locally :

```
docker run -p 8080:8080 -e JAVA_OPTS="-Dserver.port=8080 -Xms768M -Xmx768M" -e NAME="TEST" --cpus="1" --memory=1024mb ecs-perf
```

Stepping into the container

```
docker run -p 8080:8080 -e JAVA_OPTS="-Dserver.port=8080" -it --rm --entrypoint /bin/sh ecs-perf
```

To deploy the container to an ECS cluster:

## ECS and ECR

Create an ECR Repository in the target account

To push an image to ECR you need to login with the Docker client using the following method:

```
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com
```

Then create an image with the same of the registry that you created:

```
docker build -t <aws account id>.dkr.ecr.us-east-1.amazonaws.com/ecs-perf .
```

Then push the image with the following command (you will want to run the docker build command above with the ECR name `docker build -t <aws account id>.dkr.ecr.us-east-1.amazonaws.com/ecs-perf .` )

```
docker push <aws account id>.dkr.ecr.us-east-1.amazonaws.com/ecs-perf
```

Then you can either deploy manually into AWS or use the following commands:

```
aws cloudformation package \
    --profile <profile-name> \
    --region us-east-1 \
    --template-file deploy/app.yaml \
    --output-template deploy/packed-app.json \
    --s3-bucket <bucket-name>  \
    --use-json
```

The cloudformation setup is specific to the way we deploy into vpcs, so it won't correlate directly to your setup.  So you will probably have to do some editing here to make things work for your environment.  

```
aws cloudformation deploy \
   --profile <profile> \
   --region us-east-1 \
   --template-file deploy/packed-app.json  \
   --stack-name <stack-name> \
   --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
   --parameter-overrides \
        ResourcePrefix=<stack-name> \
	      Environment=test \
        PublicCIDRA=10.100.104.0/24 \
        PublicCIDRB=10.100.105.0/24 \
        PrivateCIDRA=10.100.106.0/24 \
        PrivateCIDRB=10.100.107.0/24
```

## Results

### Cpu

Effect of various command line options when using the docker run command:

- `--cpus=".25"` - sets the hard limit of usable cpu power.  Setting this value is similar to setting the cpu value in the TaskDefinition in the ECS configuration.
- `--cpu-shares=256` - sets the soft limit of usable cpu power where 256 = .25 of a cpu.  Setting this value is similar to setting the cpu value in the ContainerDefinitions section of the task in the ECS configuration.

In ECS, setting cpu in the ContainerDefinitions section is a soft reservation and the container can consume additional available cpu cycles when available.  For cost conscious deployments or workloads that only hit one or two service types at any one time, setting this value low would allow us to over provision resources without any real performance impact.  However, if you then try to use `ECSServiceAverageCPUUtilization` as a metric to scale containers you're in for a surprise.  Since `ECSServiceAverageCPUUtilization` is calculated by dividing the current cpu utilization by the cpu reservation you frequently get values in excess of 100%.  Essentially this metric becomes unusable for deployments of this type.  What's worse, java spring boot applications can consume lots of cpu when starting (1-3 processors) causing immediate scaling storms when your first deploy an environment.  AWS ECS or scaling policies really should provide an option to exclude CPU of starting containers for a period of time to prevent scaling events because of this.  

### Memory

Available heap values based on container sizing (no xms or xmx setting)

Docker memory vs available heap memory

One thing to note, setting -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap still resulted in the same max heap size for small memory sizes most likely due to the default MaxRAMFraction.  Either that or the amazoncorretto:8 having this option built in.

- 256mb docker - 121mb heap
- 512mb docker - 123mb heap
- 1024mb docker - 247mb heap
- 2048mb docker - 494mb heap
- 4096mb docker - 989mb heap
- 6144mb docker - 1484mb heap
- 8192mb docker - 1979mb heap

Java behaves this way because it assumes it needs to be nice and share memory with other on this system so it sets the max heap to be a small portion of the available system memory.  Obviously this doesn't make sense for containers. Even setting UseCGroupMemoryLimitForHeap doesn't solve the problem as the system still relies on the XX:MaxRAMFraction for determining the heap size.  MaxRAMFraction only takes integers with 1 being 100% and 2 being 50%.  We know that setting this value to 1 and MaxRAM=ContainerMemory will eventually cause an OutOfMemoryError.  So the only realistic value this variable can be assigned is 2, resulting in a heap 1/2 the size of the container memory which is inefficient.  There is a trick of setting the MaxMemory to an amount 30% lower than the container memory and setting MaxRAMFraction to 1 but you might as well just set Xmx to that value and be done with it.  In my own opinion, Java 8 isn't the best framework to containerize.  

Setting Xms and Xmx values has no effect on the available heap memory though the JAVA_OPTS or JAVA_OPTIONS environment variables as indicated by several sources.  I was only able to get the values to work when they are set directly on the java command.  Additionally setting these values in the application.properties did not work.  In my case, passing an environment variable like JAVA_OPTS to docker only had an effect if that variable was resolved and placed directly on the command to `java` when launching the app.

For ECS, the memory utilization metric calculation seems to be dependent on the MemoryReservation value when set in the ContainerDefinitions section.  For my test, the container Memory was set to 1024m and MemoryReservation was set to 512m.  Both -Xms and -Xmx were set to 768m, roughly ~30% less than the max 1024mb allowed for the container.  When looking at the ECS.MemoryUtilization metric for this service in CloudWatch, the value displayed 148%.  So in this case, it's pretty clear that the percentage is calculated off the reservation value and not the ceiling.  For managed frameworks with automatic garbage collection, reclamation of memory isn't predictable and in the case of Java 8 seems quite greedy.  Against conventional wisdom, it might make sense to set the lower and upper bound individually and set the memory reservation somewhere in between and scale when the value exceeds the reservation.  However in testing once the java memory subsystem grabs memory it never lets go.  Even forcing a gc didn't result in a smaller total heap footprint.  The conclusion is that trying to scale based on container memory utilization for java isn't effective because of the greedy way memory management is handled within the vm.  You can test this yourself with the `/add/memory/10/600` operation (this consumes 600mb of memory for 10 seconds), then after the simulated memory load completes, run a `/forcegc`, then compare the results of `/java` and `docker stats`.

### Spring application properties

Only environment variables described by ${VAR} are included.  The short form, $VAR, are not resolved properly.

### Environment variables

It's recommended to always include a default so that if the environment variable can't be resolved it doesn't cause the container to fail ex. ` --server.port=${LISTEN_PORT:-80}`.  In the Dockerfile, using the array form of the ENTRYPOINT command not resolve any environment variables.  You must use the string form shown below.

- ERROR - `ENTRYPOINT ["java", "$JAVA_OPTS", "-cp", "app:app/lib/*", "com.example.springboot.Application"]`
- WORKING - `ENTRYPOINT java $JAVA_OPTS -cp "app:app/lib/*" com.example.springboot.Application`

## Links

[Good post about jvm memory settings](https://medium.com/adorsys/jvm-memory-settings-in-a-container-environment-64b0840e1d9e)