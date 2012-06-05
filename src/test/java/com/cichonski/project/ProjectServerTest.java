package com.cichonski.project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.cichonski.project.ProjectServer;
import com.cichonski.project.client.Client;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * <p>
 * Functional tests for ProjectServer, some tests are batched to save on having
 * to spin up the server multiple times. See {@link Client} to perform
 * heavier load testing on a server running in a different (remote or local)
 * process.
 * </p>
 */
public class ProjectServerTest extends TestCase {
	// number of request threads to use for testing
	// this is set to 50 b/c due to the limitations of my development system, should be able to raise to 100.
	private static final int THREADS = 50;
	// requests each thread should send
	private static final int REQUESTS = 20;
	// default port-number
	private static final int DEFAULT_PORT = 8189;
	
	
	public ProjectServerTest(String testName) {
		super(testName);
	}

	/**
	 * @return the suite of tests being tested
	 */
	public static Test suite() {
		// adds all methods beginning with 'test' to the suite.
		return new TestSuite(ProjectServerTest.class);
	}

	/**
	 * <p>
	 * Will test:
	 * <ol>
	 * <li>that the correct requestNumber was persisted upon shutdown.</li>
	 * <li>that the correct number/order of logs were created.</li>
	 * <li>that the access log maintains ordered request counts.</li>
	 * <li>that the server re-loads the persisted requestCount when re-spawn</li>
	 * </ol>
	 * 
	 * </p>
	 * 
	 * @throws Exception
	 *             - throwing Exception for convenience, tests need to be fixed
	 *             if they fail.
	 */
    public void testAtomicFileGeneration() throws Exception
    {
    	// SETUP
    	File requestCountStorage = File.createTempFile(".projectRequestCount", ".txt");
    	requestCountStorage.deleteOnExit();
    	File accessLogStorage = File.createTempFile("projAccess", ".log");
    	accessLogStorage.deleteOnExit();
    	setup(requestCountStorage, accessLogStorage);
    	
		// TEST PERSISTED REQUEST NUMBER
		long expectedNumberOfRequests = THREADS * REQUESTS;
		//first test persisted request
		assertEquals(expectedNumberOfRequests, parseRequestNumber(requestCountStorage));
		
		// TEST ACCESS LOG GENERATION
		// now test that the access logs were generated in the correct order
		BufferedReader reader = new BufferedReader(new FileReader(accessLogStorage));
		String line = null;
		int expectedRequestNumber = 0;
		while ((line = reader.readLine()) != null){
			//just test request number, which should to be at end
			line = line.trim();
			String[] entries = line.split(" ");
			long requestNumber = Long.parseLong(entries[entries.length - 1]);
			assertEquals(expectedRequestNumber, requestNumber);
			expectedRequestNumber++;
		}
		assertEquals(expectedNumberOfRequests, expectedRequestNumber);

		
		// NOW TEST THAT SERVER WILL RE-SPAWN UP USING PERSISTED COUNT
		ProjectServer server = new ProjectServer(100, DEFAULT_PORT, requestCountStorage, accessLogStorage);
		// this is actually the same as the previous number of requests based on the semantics of what is contained in the response
		long expectedNextRequstCount = expectedNumberOfRequests;
		long actualRequestCount;

		Socket s = null;
		try {
			s = new Socket("localhost", DEFAULT_PORT);
			BufferedReader socketReader = new BufferedReader(
					new InputStreamReader(s.getInputStream()));
			String[] words = socketReader.readLine().split(" ");
			// the request number is the last thing on the line
			actualRequestCount = Long.parseLong(words[words.length - 1].trim());
		} finally{
			if (s != null){
				s.shutdownOutput();
				s.close();
			}
		}
		assertEquals(expectedNextRequstCount, actualRequestCount);
		server.shutDown();
    }


    
    /** helper method to parse out the persisted requestNumber */
    private long parseRequestNumber(File file) throws Exception{
    	BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(file));
			String line = reader.readLine();
			if (line != null && !line.isEmpty()){
				line = line.trim();
				return Long.parseLong(line);
			}
		} finally {
			if (reader != null){
				try {reader.close();} 
				catch (IOException e) {};
			}
		}
		return 0;
    }
    
    /**
     * Helper method to setup server, run some requests shut things down gracefully. Will use THREAD and REQUEST defaults.
     * @param requestCountStorage - file to store requestCount (should be a temp)
     * @param accessLogStorage - file to store the access logs (should be a temp)
     * @param ensureAccessLogOrder - should the server ensure the logs are ordered correctly?
     */
    private void setup(File requestCountStorage, File accessLogStorage) throws IOException {
    	// not using default JUNIT since I need some more control
    	ProjectServer server = new ProjectServer(100, DEFAULT_PORT, requestCountStorage, accessLogStorage);
		// spin up some threads and test the request log that gets generated
		ExecutorService service = Executors.newFixedThreadPool(100);
		for (int i = 0; i < THREADS; i++) {
			service.execute(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < REQUESTS; i++) {
							Socket s = new Socket("localhost", DEFAULT_PORT);
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
