package com.cichonski.project.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.cichonski.project.ProjectServer;

/**

 * <p>
 * Note: while each thread/client will receive an incremented requestCount, it is not
 * guaranteed that the server will return responses to multiple threads/clients
 * in the exact order they were received, but the server will ensure that the
 * requestCount returned reflects the position at the time that request was
 * received. See {@link ProjectServer} docs for more detail.
 * </p>
 * 
 * 
 */
public class Client {
	// using default java logger and console output to reduce external dependencies/configuration.
	private static final Logger LOGGER = Logger.getLogger(Client.class.getName());
	// number of threads to send against the server
	private static final int THREADS = 100;
	// number of requests per thread
	private static final int REQUESTS = 20;
	// address of the remote host
	private static final String HOST = "localhost";
	// port of remote host
	private static final int PORT = 8189;
	
	public static void main(String[] args){
		final BlockingQueue<Long> requestQueue = new LinkedBlockingQueue<Long>();
		
		ExecutorService service = Executors.newFixedThreadPool(100);
		for (int i = 0; i < THREADS; i++) {
			service.execute(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < REQUESTS; i++) {
							Socket s = new Socket(HOST, PORT);
							BufferedReader reader = new BufferedReader(
									new InputStreamReader(s.getInputStream()));
							String line = reader.readLine();
							System.out.println(line);
							if (line != null && !line.isEmpty()){
								String[] words = line.split(" ");
								// the request number is the last thing on the line
								long actualRequestCount = Long.parseLong(words[words.length - 1].trim());
								requestQueue.add(actualRequestCount);
							}
							
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
				// just wait until everything is done and force the exit.
				break;
			}
		}
		// inspect the request queue
		int expectedSize = THREADS * REQUESTS;
		Set<Long> requests = new TreeSet<Long>();
		// will remove dupes (which should not exist) and order).
		requestQueue.drainTo(requests);
		int realSize = requests.size();
		if (expectedSize != realSize){
			LOGGER.warning("failure: expected number of requests was: " + expectedSize + ", but real size was: " + realSize);
		} else {
			LOGGER.info("success: received all " + expectedSize + " responses from server");
		}
		
		
		
	}

}
