package com.cichonski.project.logging;

import java.net.Socket;

/**
 * <p>
 * Provides an abstraction over the handling of access logs and the tracking of
 * request counts so that different implementations may be created for different
 * performance goals.
 * </p>
 * <p>
 * There is currently one implementation, {@link AtomicAccessLogHandler} that
 * guarantees the correct ordering of access logs. Another potential
 * implementation would be a non-atomic log handler that provided no guarantee
 * of log order, but would might be more performant in high-load situations;
 * more testing/profiling is required to determine if that type of
 * implementation is necessary.
 * </p>
 * 
 */
public interface AccessLogHandler extends Runnable {
	
	/**
	 * <p>
	 * Takes a socket representing the incoming request records that request in
	 * a server-level request counter. This method then generates the
	 * appropriate access log. This method will not alter the socket state in
	 * any way.
	 * </p>
	 * 
	 * @param socket
	 *            - the socket representing the request to log.
	 * @return - the count of previous requests processed by this handler before
	 *         the specific socket.
	 */
	long recordAndLogMessage(Socket socket);
	
	/**
	 * <p>
	 * Will clean up the resources of this handler and persist any remaining log
	 * buffer to disk. This method will leave the handler in an unusable state,
	 * so clients should call this upon server shutdown.
	 * </p>
	 */
	void cleanUp();
		
	
	
}
