package com.example.springboot;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.atomic.AtomicInteger;
import java.lang.*;
import java.util.ArrayList;
import java.util.List;

@RestController
public class LoadController {

   @Value("${spring.application.name}")
   private String name;

	@RequestMapping("/")
	public String index() {
		return "Apis /add/cpu/{seconds}/{threads} | /add/memory/{seconds}/{mb} | /java";
	}

	@RequestMapping("/liveness_check")
  public ResponseEntity<String> livenessCheck(){
  	return new ResponseEntity<String>("Service is alive", HttpStatus.OK);
	}

	@RequestMapping("/props")
	public String props() {
		return this.name;
	}

	@RequestMapping("/java")
	public String javaInfo() {
		String version = System.getProperty("java.version");
		Runtime runtime = Runtime.getRuntime();
		long maxMemory = runtime.maxMemory();
		long totalMemory = runtime.totalMemory();
		long freeMemory = runtime.freeMemory();
		long usedMemory = totalMemory - freeMemory;
		int procCount = runtime.availableProcessors();

		return String.format("Java version: %s; Procs: %s, Memory (used,total,max): %s / %s / %s ", 
					version, 
					procCount, 
					memoryFormatPretty(usedMemory),
					memoryFormatPretty(totalMemory), 
					memoryFormatPretty(maxMemory)
				);
	}

	@RequestMapping("/add/cpu/{seconds}/{threads}")
	public String addCpuLoad(@PathVariable("seconds") int seconds, @PathVariable("threads") int threads) {
		for (int i = 0; i < threads; i++) { 
			Thread thread = new Thread(new CpuLoadThread(seconds));
			thread.start();
		}
		return String.format("%s threads started running for %s seconds", threads, seconds);
	}

	@RequestMapping("/add/memory/{seconds}/{mb}")
	public String addMemoryLoad(@PathVariable("seconds") int seconds, @PathVariable("mb") int mb) {

		Thread thread = new Thread(new MemoryLoadThread(seconds, mb * 1024 * 1024));
		thread.start();

		return String.format("memory load started %smb", mb);
	}

	@RequestMapping("/forcegc")
	public String forcegc() {
		Runtime runtime = Runtime.getRuntime();
		runtime.gc();
		return "Forcing garbage collection";
	}

	private String memoryFormatPretty(long memory) {
		double kb = memory / 1024;
		double mb = kb / 1024;
		if (mb < 1024) {
			return String.format("%dmb", (long)mb);
		}
		double gb = mb / 1024;
		return String.format("%dgb", (long)mb);
	}

}


class CpuLoadThread implements Runnable {

	private long seconds;

	private static final AtomicInteger counter = new AtomicInteger(0);

	public CpuLoadThread(long seconds) {
		this.seconds = seconds;
	}

    public void run() 
    {
			try {
				this.simulateLoad();
			} catch (InterruptedException e) {
				System.out.println(e);
			}
    }

	private void simulateLoad() throws InterruptedException {
		int id = CpuLoadThread.counter.incrementAndGet();
		System.out.println(String.format("Simulating cpu load %s", id));
		
		long time = System.currentTimeMillis() + this.seconds * 1000;
		long sum = 10;
		// artificial load, anything works here
    while (System.currentTimeMillis() < time) {
			for (int i = 0; i < 100; i++) {
				sum = sum + (long)sum / 4;
			}
			Thread.sleep(10);
    }
		System.out.println(String.format("Simulated cpu load %s complete", id));
	}
}

class MemoryLoadThread implements Runnable {

	private long seconds;

	private long bytes;

	private final List<byte[]> allocatedMemory;

	private static final AtomicInteger counter = new AtomicInteger(0);

	public MemoryLoadThread(long seconds, long bytes) {
		this.seconds = seconds;
		this.bytes = bytes;
		this.allocatedMemory = new ArrayList<>();
	}

    public void run() 
    {
		try {
			this.simulateLoad();
		} catch (InterruptedException e) {
			System.out.println(e);
		} 
    }

	private void simulateLoad() throws InterruptedException {
		int id = MemoryLoadThread.counter.incrementAndGet();
		System.out.println(String.format("Simulating memory load %s", id));
		long loadToAllocate = this.bytes;
        if (loadToAllocate < Integer.MAX_VALUE) {
            allocatedMemory.add(new byte[(int)loadToAllocate]);
        } else {
            int modulo = Math.toIntExact(loadToAllocate % Integer.MAX_VALUE);
            int times = Math.toIntExact((loadToAllocate - modulo) / Integer.MAX_VALUE);

            for (int i = 0; i < times; i++) {
                allocatedMemory.add(new byte[Integer.MAX_VALUE]);
            }

            allocatedMemory.add(new byte[modulo]);
        }
		Thread.sleep(this.seconds * 1000);
		System.out.println(String.format("Simulated load %s complete", id));
	}
}

