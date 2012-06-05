package com.cichonski.project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.cichonski.project.logging.AccessLogHandler;
import com.cichonski.project.logging.AtomicAccessLogHandler;

/**
 * <p>
 * Clients may use this class in two ways, first by spawning a default instance
 * thru a call to the main() method, second by creating an instance of this
 * class with a a custom configuration. The second option allows the client to
 * the server shutdown() method to gracefully stop the server
 * </p>
 * <p>
 * The server guarantees that access logs will be output in order of the
 * requestCount associated with the request. However, while each thread/client
 * will receive an incremented requestCount, it is not guaranteed that the
 * server will return responses to multiple threads/clients in the exact order
 * they were received, but the server will ensure that the requestCount returned
 * reflects the position at the time that request was received (i.e., a
 * multithreaded client printing responses to a console may see slight
 * mis-ordering for cross-thread responses.).
 * </p>
 * 
 * 
 */
public class ProjectServer {
	// using default java logger and console output to reduce external dependencies/configuration.
	private static final Logger LOGGER = Logger.getLogger(ProjectServer.class.getName());
	// default number of threads to set as upper bound.
	private static final int DEFAULT_MAX_THREADS = 100;
	// default port to run instance on
	private static final int DEFAULT_PORT = 8189;
	// default folder to store files such as requestBackup and access logs
	private static final String DEFAULT_STORAGE_FOLDER = System.getProperty("user.home") + System.getProperty("file.separator");
	// default file to store requestBackup
	private static final String DEFAULT_REQUEST_COUNT_STORAGE = DEFAULT_STORAGE_FOLDER + ".projReqCount.txt";
	// default file to store logs
	private static final String DEFAULT_ACCESS_LOG_STORAGE = DEFAULT_STORAGE_FOLDER + "access.log";
	
	// thread-safe way to count requests across threads
	private final AtomicLong requestCount;
	// actual location to store requestCount data
	private final File requestCountStorageLocation;
	//handler for writing out logs from the queue
	private final AccessLogHandler accessLogHandler;
	// thread to manage socket server
	private final Thread serverThread;
	// Server Socket that this class manages
	private final ServerSocket server;
	// threadpool for handling requests
	private final ExecutorService threadPool;

	/**
	 * <p>Construct a new instance of a ProjectServer. Returned instance will already be accepting requests on specified port.</p>
	 * @param maxThreads - largest number of threads that may execute at once.
	 * @param portNumber - port to run server on.
	 * @param requestCountStorageLocation - the file location to backup the request count.
	 * @param accessLogStorage - the file location to store access logs
	 * @param ensureLogOrder - true if extra resources should be expended to synchronize log writes to preserve request ordering.
	 * @throws IOException - if errors occur with server creation.
	 */
	public ProjectServer(int maxThreads, int portNumber, File requestCountStorageLocation, File accessLogStorage) throws IOException {
		this.requestCountStorageLocation = requestCountStorageLocation;
		this.requestCount = initializeRequestCount();
		this.accessLogHandler = new AtomicAccessLogHandler(accessLogStorage, requestCount);
		this.server = new ServerSocket(portNumber);
		this.threadPool = Executors.newFixedThreadPool(maxThreads);
		
		//initialize and start processing requests.
		intializeServer();
		this.serverThread = createServerThread();
		this.serverThread.start();
	}
	
	/** package private constructor for testing */
	ProjectServer() throws IOException{
		this(DEFAULT_MAX_THREADS, DEFAULT_PORT, new File(DEFAULT_REQUEST_COUNT_STORAGE), new File(DEFAULT_ACCESS_LOG_STORAGE));
	}

	
	/**
	 * <p>
	 * Main method provided for clients wishing to spawn an instance of the
	 * server using default configuration values (default port: 8189). Note:
	 * using the main method currently has no graceful way of shutting down
	 * server, command line clients should use ctrl+c.
	 * </p>
	 */
	public static void main(String[] args) {
		try {
			 new ProjectServer(DEFAULT_MAX_THREADS, DEFAULT_PORT, new File(DEFAULT_REQUEST_COUNT_STORAGE), new File(DEFAULT_ACCESS_LOG_STORAGE));
		} catch (IOException e){
			// if an error occurs with server start-up there is nothing we can do, check configuration.
			LOGGER.log(Level.SEVERE, "Could not start ProjectServer, error initializing server", e);
		}
	}
	
	
	/**
	 * <p>Method that will signal the server to stop accepting requests, persist necessary data, and
	 * shutdown.</p>
	 */
	public void shutDown() {
		serverThread.interrupt();
		// this may get covered by the shutdownHook, but not always and it won't hurt if it is duplicate
		persistRequestCount();
		accessLogHandler.cleanUp();
		// this is not guaranteed to finish, but there is no need to wait since
		// we are shutting down.
		threadPool.shutdownNow();
		try {
			if (server != null && !server.isClosed()) {
				LOGGER.info("Server shutting down, SocketExceptions may occur");
				server.close();
			}
		} catch (IOException e) {
			LOGGER.log(Level.WARNING,
					"Error occurred while closing Server Socket", e);
		}
		// no need to call anything that is handled by ShutdownHook.
	}
	
	
	/** helper method to register shutdown hook and spin up necessary handlers */
	private void intializeServer(){
		// spin up thread to manage writing of access logs in batch.
		final Thread accessLogPusher = new Thread(accessLogHandler);
		accessLogPusher.start();
		
		// register shutdown hook for persisting requestCount and logs upon termination
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){
			public void run() {
				persistRequestCount();
				accessLogHandler.cleanUp();
			}
		}));
	}
	
	
	/**
	 * Helper method to generate the thread containing the ServerSocket and the code that dispatches requests to worker threads.
	 * @return Thread to run the ServerSocket.
	 */
	private Thread createServerThread() {
		// note: this needs to be in a child thread so that the parent thread can manage it better (i.e., interrupt upon shutdown).
		// Originally has ProjectServer itself implemented as a Runnable, but was having all sorts of issues.
		// this is an anonymous inner class to get easy access to surrounding fields.
		Thread t = new Thread(new Runnable() {
			public void run() {
				try {
					while (!Thread.currentThread().isInterrupted()) {
						// kick off main worker threads to handle connections.
						ConnectionHandler handler = new ConnectionHandler(server.accept(), accessLogHandler);
						threadPool.execute(handler);
					}
				} catch (SocketException e){
					if (Thread.currentThread().isInterrupted()) {
						//assume this is b/c of shutdown switch
						LOGGER.log(
								Level.INFO,
								"SocketException occurred while accepting connection from the Server Socket after thread has been interrupted, this may be normal if server was shutdown.");
					}
				} catch (IOException e) {
						LOGGER.log(
								Level.WARNING,
								"Exception occurred while accepting connection from the Server Socket",
								e);
				} finally {
					// may be a duplicate call, but that is okay as it expects it.
					shutDown();
				}
			}
		});
		return t;
	}

	

	/**
	 * Simple helper method to persist the request count upon server termination.
	 */
	private void persistRequestCount() {
		PrintWriter writer = null;
		try {
			writer = new PrintWriter(requestCountStorageLocation);
			writer.print(requestCount.get());
			writer.flush();
		} catch (FileNotFoundException e) {
			LOGGER.log(
					Level.WARNING,
					"Could not persist request count, count will start from 0 upon re-initialization",
					e);
		} finally {
			if (writer != null){
				writer.close();
			}
		}
	}
	
	/**
	 * Simple helper method to initialize the requestCount to the last saved
	 * value, or 0 if no value was saved.
	 */
	private AtomicLong initializeRequestCount(){
		BufferedReader reader = null;
		// if the value cannot be set, default should be 0
		AtomicLong restoredCount = new AtomicLong(0);
		try {
			reader = new BufferedReader(new FileReader(requestCountStorageLocation));
			String line = reader.readLine();
			if (line != null && !line.isEmpty()){
				line = line.trim();
				restoredCount = new AtomicLong(Long.parseLong(line));
			}
		}  catch (NumberFormatException e){
			//just default to 0.
			LOGGER.log(
					Level.INFO,
					"Could not find a persisted request count, count will start from 0.");
		}   catch (IOException e) {
			// can't find the value, just default to 0
			LOGGER.log(
					Level.INFO,
					"Could not find a persisted request count, count will start from 0.");
		} finally {
			if (reader != null){
				try {reader.close();} 
				catch (IOException e) {LOGGER.info("could not close file reader");};
			}
		}
		return restoredCount;
	}
	
	/* Following code contains inner class declarations for Runnable Handlers that are used by worker threads, implementing as inner classes since they relate directly to the Server */ 

	/**
	 * <p>
	 * Simple runnable to handle incoming connections 
	 * </p>
	 * 
	 */
	private static class ConnectionHandler implements Runnable {
		private final Socket socket;
		private final AccessLogHandler accessLogHandler;

		private ConnectionHandler(Socket socket, AccessLogHandler accessLogHandler) {
			this.socket = socket;
			this.accessLogHandler = accessLogHandler;
		}

		public void run() {
			long previousRequesetCount = accessLogHandler.recordAndLogMessage(socket); 
			// write log when request is received.
			StringBuilder responseBuilder = new StringBuilder();
			responseBuilder.append("Number of prior requests at the time of this connection: ").append(
					previousRequesetCount);
			PrintWriter out = null;
			try {
				try {
					out = new PrintWriter(socket.getOutputStream(), true);
					out.println(responseBuilder.toString());
				} finally {
					socket.shutdownOutput();
					socket.close();
				}
			} catch (IOException e) {
				// this is thrown from both try and finally, essentially for same reason.
				LOGGER.log(Level.WARNING,
						"Failed to return response data to client", e);
			}
		}

	}
	
	
	
	
}
