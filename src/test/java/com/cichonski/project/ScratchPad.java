package com.cichonski.project;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for doing sporadic testing of functionality.
 *
 */
public class ScratchPad {
	// number of request threads to use for testing
	// this is set to 50 b/c due to the limitations of my development system, should be able to raise to 100.
	private static final int THREADS = 50;
	// requests each thread should send
	private static final int REQUESTS = 50;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException {

		
		
    	// not using default JUNIT since I need some more control
    	ProjectServer server = new ProjectServer();
		// spin up some threads and test the request log that gets generated
		ExecutorService service = Executors.newFixedThreadPool(100);
		for (int i = 0; i < THREADS; i++) {
			service.execute(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < REQUESTS; i++) {
							Socket s = new Socket("localhost", 8189);
							BufferedReader reader = new BufferedReader(
									new InputStreamReader(s.getInputStream()));
							// this needs to be here or request never goes through
							reader.readLine();
							s.close();
							//average network latency according to verizon, would be better to randomize
							TimeUnit.MILLISECONDS.sleep(45);
						}
					} catch (IOException e) {
						e.printStackTrace();
					} catch (InterruptedException e){
						e.printStackTrace();
					}
				}
			});

		}
		service.shutdown();
		while (true) {
			if (service.isTerminated()) {
				// just wait until everything is done.
				server.shutDown();
				break;
			}
		}
	}

}
