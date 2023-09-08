package io.github.social.nostr.relay.utilities;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class LogService {

	public static enum LogLevel {
		INFO, SUCCESS, DEBUG, WARN, ERROR;
	}

	public static final LogService INSTANCE = new LogService();
	
	public static final LogService getInstance(final String name) {
		 return new LogService(name);
	}

	private LogService() { /***/ }

	private String name = "";

	private LogService(final String name) {
		this();
		this.name = String.format(" [%s]", name);
	}
	
	public byte info(final CharSequence template, final Object... args) {
		return logv(template, LogLevel.INFO, args);
	}
	
	public byte debug(final CharSequence template, final Object... args) {
		return logv(template, LogLevel.DEBUG, args);
	}
	
	public byte error(final CharSequence template, final Object... args) {
		return logv(template, LogLevel.ERROR, args);
	}
	
	public byte error(final CharSequence message, final Throwable throwable) {
		final StringBuilder sb = new StringBuilder(message);
		
		Throwable caused = throwable;
		
		while(true) {
			for(StackTraceElement element: caused.getStackTrace()) {
				final String className = element.getClassName();
				final String methodName = element.getMethodName();
				final String fileName = element.getFileName();
				final int lineNumber = element.getLineNumber();
				final String trace = String.format("%n  at %s#%s (%s:%d)", className, methodName, fileName, lineNumber);
				sb.append(trace);
			}

			caused = caused.getCause();
			if( caused == null ) { break; }

			sb.append(String.format("%nCaused by %s", caused.getMessage()));
		}

		return logv(sb.toString(), LogLevel.ERROR);
	}

	public byte warning(final CharSequence template, final Object... args) {
		return logv(template, LogLevel.WARN, args);
	}
	
	private byte logv(final CharSequence template, final LogLevel level, final Object... args) {
		computeLog(template, level, args);

		return 0;
	}

	private byte computeLog(final CharSequence template, final LogLevel level, final Object... args) {
		String messageFormatted = template.toString();
		
		for(int i = 0; i < args.length; ++i) {
			final String d = args[i] != null ? args[i].toString() : ""; 
			messageFormatted = messageFormatted.replaceFirst("\\{\\}", d);
		}
		messageFormatted = messageFormatted.replaceAll("\\{\\}", "");

		return log(messageFormatted, level);
	}

	private byte log(final String message, final LogLevel level) {
		final String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss.SSS", Locale.US));

		final String outMessage = String.format("%s [%5s]%s%n%s%n%n", dateTime, level.name(), name, message);

		switch(level) {
			case INFO: return printOut(outMessage);
			case DEBUG: return printOut(outMessage);
			case WARN: return printErr(outMessage);
			case ERROR: return printErr(outMessage);
			default: return 0;
		}
	}
	
	private byte printOut(final String message) {
		return print(System.out, message);
	}
	
	private byte printErr(final String message) {
		return print(System.err, message);
	}

	private byte print(final PrintStream writer, final String message) {
	//private byte print(final Writer writer, final String message) {
		writer.print(message);
		// try {
		// 	writer.write(message);
		// } catch (IOException e) { /***/ }

		return 0;
	}

}
