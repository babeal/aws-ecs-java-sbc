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

```shell
docker build -t ecs-perf .
```

Run the container locally:

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

### Metrics

Using reservation values for services creates a unique problem when trying to place tasks.  For this discussion lets say you have a cluster with a single EC2 instance with 2 processors.  2 processors equals 2048 available shares before placing any tasks.  Then you place one task that reserves 1.5 cpu(s) or 1536 cpu shares.  When the system gets busy and it's time to add another task, it will fail as ECS can't find a EC2 host with enough available shares on which to place the task.  You would think the system would be smart enough to detect this and launch another EC2 instance but it's not.  You have to do this yourself by setting an alarm on the CPU reservation and telling the system to scale up when there isn't enough space.  Seems easy enough, however the metric is calculated across the entire cluster.  So there is still a possibility that the cluster has 1536 shares available, but not on a single machine, so you still end up with the task can't be placed error.  This also has a solution by using bin-packing placement strategies, but my observation is this approach results in extra EC2 hosts that basically go unused.  It doesn't seem very cost efficient does it.  Essentially, I want ECS to be smart enough automatically scale EC2 when it can't place a new task and automatically redistribute the load.  Can't have everything I guess.

Since task/service CPU and Memory aren't reliable what other metrics can we use?  There are other metrics on the load balancer / target group like 5XX errors, 4XX errors, Request Count, Target Response Time.  So far I haven't been able to use them in a generic fashion successfully.  The 5XX errors supposedly are only counted when coming from the load balancer.  So 502 Bad Gateway and 503 Service Unavailable would tell the ECS that the current set of tasks can't handle the load and to create more.  For me, reacting to this metric is too late.  Additionally, if you have a container that is having a problem starting, the error is the same so the system starts scaling, exactly what you don't want to happen.  Request count and Request count per target are a possibility.  This metric doesn't cause scaling events on deployment and is a representation of actual load, however, it requires a significant amount of effort load testing your services on the exact EC2 instance types it will be run on to ensure you have the step values configured correctly or you risk under-scaling or over-scaling.  Target response time is another one that is highly dependent on other factors which could make it difficult to tune.  You need to watch the service's target response time under load to determine an appropriate threshold and continue to monitor it over time.  Neither of the last two metrics would work for scaling this project as the load isn't related the frequency of requests.  Sadly it appears custom application level metrics are the way to go.

### Scaling Policies

The two types discussed here are Target Tracking and Step Policies.  Step policies are pretty easy to understand.  You define an alarm on a metric, then define steps in the StepScalingPolicyConfiguration that define how much should be scaled out or scaled in based on the value of the metric.  Target tracking attempts to keep the value of a metric around a set point.  For example if you've set a target tracking policy on CPU @ 50% and the value of CPU is 60%, then the system might add more instances to reduce the value of the metric.  My observation for small clusters is that adding or removing instances causes significant movement in the tracked metric creating a constant wave of scaling in and out which is undesirable.  Target tracking needs additional configuration options like dead zone to make it really useful.  Even more useful, is allowing dead zone to be a function of instance count.  This way I can scale up initially on a loose definition but as the cluster grows I can tighten things up.  At this time, effective target tracking causes too much unused capacity. 

## Notes

### Notable ECS behaviors

- 502 error - returned by the load balancer when 0 targets are registered
- 503 error - returned when a target is registered with the load balancer but is unavailable or still starting

### Build Packs / Paketo.io

While I was researching java heap memory I ran into the concept of build packs, specifically [paketo.io](https://paketo.io/).  Their Java Maven pack seemed to auto calculate the correct heap memory before starting the java process in the container.  But I kept running into errors which claimed the pom.xml wasn't found in the workspace.  Also it kept trying to install Java 11 even though I set the environment variable to 8.x.  Looking a little deeper this seems like another Google Bazel-ish type product with lots of promise and no documentation.  Hopefully I'll be able to talk a look at this again later when I have more time for discovery.

### Spring application properties

Only environment variables described by ${VAR} are included.  The short form, $VAR, are not resolved properly.

### Environment variables

It's recommended to always include a default so that if the environment variable can't be resolved it doesn't cause the container to fail ex. ` --server.port=${LISTEN_PORT:-80}`.  In the Dockerfile, using the array form of the ENTRYPOINT command not resolve any environment variables.  You must use the string form shown below.

- ERROR - `ENTRYPOINT ["java", "$JAVA_OPTS", "-cp", "app:app/lib/*", "com.example.springboot.Application"]`
- WORKING - `ENTRYPOINT java $JAVA_OPTS -cp "app:app/lib/*" com.example.springboot.Application`

## Links

- [Good post about jvm memory settings](https://medium.com/adorsys/jvm-memory-settings-in-a-container-environment-64b0840e1d9e)
- [JVM garbage collection tuning ](https://docs.oracle.com/javase/8/docs/technotes/guides/vm/gctuning/sizing.html#sthref22)
- [Analyzing java memory usage in a Docker container](http://trustmeiamadeveloper.com/2016/03/18/where-is-my-memory-java/)
- [Open JDK and Containers](https://developers.redhat.com/blog/2017/04/04/openjdk-and-containers/)
