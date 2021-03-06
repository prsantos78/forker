package com.sshtools.forker.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;

import com.sshtools.forker.client.EffectiveUserFactory.SudoFixedPasswordUser;
import com.sshtools.forker.common.IO;

/**
 * Some helper methods for running commands and doing common things with minimal
 * code, like capturing the output to a string, elevating privileges etc.
 * <p>
 * Command executions can be configured using some thread local state accessible
 * through methods such as {@link OSCommand#environment()}, {@link OSCommand#io}
 * <pre>
 * <code>
 * environment().put("ENV1", "ALWAYS_PRESENT");
 * io(IO.SINK);
 * run("rm", "/tmp/file1");
 * run("cp", "/tmp/file2", "/tmp/file3");
 * adminCommandAndCaptureOutput("cat", "/etc/shadow");
 * run("rm", "/tmp/file4");
 * reset();
 * </code>
 * </pre>
 */
public class OSCommand {

	final static Logger LOG = Logger.getLogger(OSCommand.class.getSimpleName());

	private static ThreadLocal<Boolean> elevated = new ThreadLocal<Boolean>();
	private static ThreadLocal<IO> io = new ThreadLocal<IO>();
	private static ThreadLocal<Map<String, String>> environment = new ThreadLocal<Map<String, String>>();

	private static char[] sudoPassword = null;

	/**
	 * Set the fixed password to use. This is only used when elevation is
	 * required and the daemon is not running. If it is not runiing, the
	 * password will be asked be for in some fashion. There should be little
	 * reason to use this outside of testing or development.
	 * 
	 * @param password
	 *            sudo password
	 */
	public static void sudo(char[] password) {
		sudoPassword = password;
	}

	/**
	 * Remove all thread local state, such as that applied by {@link #io(IO)},
	 * {@link #elevate()} or {@link #environment()}.
	 */
	public static void reset() {
		elevated.remove();
		io.remove();
		environment.remove();
	}

	/**
	 * Make any commands executed using this class be run as an administrator
	 * until the configuration is removed. Usually paired with a
	 * {@link #restrict()} or {@link #reset()} call, e.g.
	 * 
	 * <pre>
	 * <code>
	 * elevate();
	 * try {
	 *     run("cat", "/etc/passwd");
	 * }
	 * finally {
	 *     restrict();
	 * }
	 * </code>
	 * </pre>
	 * 
	 * @return whether or not elevation was previously set
	 */
	public static boolean elevate() {
		boolean res = !Boolean.TRUE.equals(elevated.get());
		elevated.set(Boolean.TRUE);
		return res;
	}

	/**
	 * Return the current elevation state to its state before the last
	 * {@link #elevate()}. Subsequent processes will be run as the current user.
	 * 
	 * @return was elevated
	 */
	public static boolean restrict() {
		boolean el = Boolean.TRUE.equals(elevated.get());
		elevated.set(Boolean.FALSE);
		return el;
	}

	/**
	 * 
	 * Make any commands executed using this class be run using the specified
	 * I/O mode. Usually paired with a 2nd call or {@link #reset()} call.
	 * 
	 * <pre>
	 * <code>
	 * io(IO.SINK);
	 * try {
	 *     run("cat", "/etc/passwd");
	 * }
	 * finally {
	 *     reset();
	 * }
	 * </code>
	 * </pre>
	 * 
	 * @param io
	 *            I/O mode
	 * @return previously set I/O mode
	 */
	public static IO io(IO io) {
		IO oio = io();
		OSCommand.io.set(io);
		return oio;
	}

	/**
	 * Get the currently set I/O mode.
	 * 
	 * @return I/O mode
	 * @see #io(IO)
	 */
	public static IO io() {
		return OSCommand.io.get();
	}

	/**
	 * Get the currently set environment map. This may be altered directly.
	 * 
	 * @return environment map
	 */
	public static Map<String, String> environment() {
		Map<String, String> env = environment.get();
		if (env == null) {
			env = new HashMap<String, String>();
			environment.set(env);
		}
		return env;
	}

	/**
	 * Set the current environment map.
	 * 
	 * @param env environment
	 * @see #environment()
	 */
	public static void environment(Map<String, String> env) {
		environment.set(env);
	}

	/**
	 * Simplest way to run a command. I/O will be redirected to the standard
	 * streams, and an exception will be thrown if the exit code is anything
	 * other than zero.
	 * 
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(String... args) throws IOException {
		run(null, args);
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the standard streams, and an exception will be
	 * thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(File cwd, String... args) throws IOException {
		int ret = runCommand(cwd, args);
		if (ret != 0) {
			throw new IOException("Command returned non-zero status '" + ret + "'.");
		}
		;
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the provied output stream, and an exception
	 * will be thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(File cwd, OutputStream out, String... args) throws IOException {
		int ret = runCommand(cwd, out, args);
		if (ret != 0) {
			throw new IOException("Command returned non-zero status '" + ret + "'.");
		}
	}

	/**
	 * Simplest way to run a command. I/O will be redirected to the standard
	 * streams, and an exception will be thrown if the exit code is anything
	 * other than zero.
	 * 
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(List<String> args) throws IOException {
		run((File) null, args);
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the standard streams, and an exception will be
	 * thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(File cwd, List<String> args) throws IOException {
		run(cwd, null, args);
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the provied output stream, and an exception
	 * will be thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(File cwd, OutputStream out, List<String> args) throws IOException {
		Process process = doCommand(cwd, args, out);
		if (process.exitValue() != 0) {
			throw new IOException(
					"Update process exited with status " + process.exitValue() + ". See log for more details.");
		}
	}

	/**
	 * Run a command. I/O will be redirected to the provied output stream, and
	 * an exception will be thrown if the exit code is anything other than zero.
	 * 
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void run(OutputStream out, List<String> args) throws IOException {
		run((File) null, out, args);
	}

	/**
	 * Run a command. I/O will be redirected to the standard streams, and the
	 * exit code will be returned.
	 * 
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommand(String... args) throws IOException {
		return runCommand(null, args);
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the standard streams, and the exit code will be
	 * returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommand(File cwd, String... args) throws IOException {
		return runCommand(cwd, System.out, args);
	}

	/**
	 * Run a command. I/O will be redirected to the standard streams, and the
	 * exit code will be returned.
	 * 
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommand(List<String> args) throws IOException {
		return runCommand((String[]) args.toArray(new String[0]));
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the provied output stream, and the exit code
	 * will be returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommand(File cwd, OutputStream out, String... args) throws IOException {
		LOG.fine("Running command: " + StringUtils.join(args, " "));
		List<String> largs = new ArrayList<String>(Arrays.asList(args));
		ForkerBuilder pb = new ForkerBuilder(largs);
		if (pb.io() == null)
			pb.io(io.get() == null ? IO.INPUT : io.get());
		checkElevationAndEnvironment(pb);
		if (cwd != null) {
			pb.directory(cwd);
		}
		pb.redirectErrorStream(true);
		Process p = pb.start();
		IOUtils.copy(p.getInputStream(), out == null ? new NullOutputStream() : out);
		try {
			return p.waitFor();
		} catch (InterruptedException e) {
			LOG.log(Level.SEVERE, "Command interrupted.", e);
			return -999;
		}
	}

	/**
	 * Run a command. I/O will be redirected to the provied output stream, and
	 * the exit code will be returned.
	 * 
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommand(OutputStream out, List<String> args) throws IOException {
		return runCommand((File) null, out, (String[]) args.toArray(new String[0]));
	}

	/**
	 * Run a command. I/O will be written to the provided file, and the exit
	 * code will be returned.
	 * 
	 * @param file
	 *            file to write to
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommandAndOutputToFile(File file, String... args) throws IOException {
		return runCommandAndOutputToFile(null, file, args);
	}

	/**
	 * Run a command with a particular working directory and capture all of the
	 * output to a list of strings. An exception will be thrown if the exit code
	 * is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @return output as list of strings
	 * @throws IOException
	 *             on any error
	 */
	public static Collection<String> runCommandAndCaptureOutput(File cwd, String... args) throws IOException {
		File askPass = null;
		try {
			List<String> largs = new ArrayList<String>(Arrays.asList(args));
			LOG.fine("Running command: " + StringUtils.join(largs, " "));
			ForkerBuilder pb = new ForkerBuilder(largs);
			if (pb.io() == null)
				pb.io(io.get() == null ? IO.INPUT : io.get());
			checkElevationAndEnvironment(pb);
			if (cwd != null) {
				pb.directory(cwd);
			}
			pb.redirectErrorStream(true);
			Process p = pb.start();
			Collection<String> lines = IOUtils.readLines(p.getInputStream());
			try {
				int ret = p.waitFor();
				if (ret != 0) {
					throw new IOException("Command '" + StringUtils.join(largs, " ")
							+ "' returned non-zero status. Returned " + ret + ". " + StringUtils.join(lines, "\n"));
				}
			} catch (InterruptedException e) {
				LOG.log(Level.SEVERE, "Command interrupted.", e);
				throw new IOException(e);
			}
			return lines;
		} finally {
			if (askPass != null) {
				askPass.delete();
			}
		}
	}

	/**
	 * Run a command with a particular working directory and capture all of the
	 * output to a file. The exit code will be returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param file
	 *            file to write output to
	 * @param args
	 *            command arguments
	 * @return output as list of strings
	 * @throws IOException
	 *             on any error
	 */
	public static int runCommandAndOutputToFile(File cwd, File file, String... args) throws IOException {
		LOG.fine("Running command: " + StringUtils.join(args, " ") + " > " + file);
		FileOutputStream fos = new FileOutputStream(file);
		try {
			ForkerBuilder pb = new ForkerBuilder(args);
			pb.io(io.get() == null ? IO.INPUT : io.get());
			if (cwd != null) {
				pb.directory(cwd);
			}
			pb.redirectErrorStream(true);
			checkElevationAndEnvironment(pb);
			Process p = pb.start();
			IOUtils.copy(p.getInputStream(), fos);
			try {
				return p.waitFor();
			} catch (InterruptedException e) {
				LOG.log(Level.SEVERE, "Command interrupted.", e);
				throw new IOException(e);
			}
		} finally {
			fos.close();
		}
	}

	/**
	 * Run a command and capture all of the output to a list of strings. An
	 * exception will be thrown if the exit code is anything other than zero.
	 * 
	 * @param args
	 *            command arguments
	 * @return output as list of strings
	 * @throws IOException
	 *             on any error
	 */
	public static Collection<String> runCommandAndCaptureOutput(String... args) throws IOException {
		return runCommandAndCaptureOutput(null, args);
	}

	/**
	 * Run a command as administrator. I/O will be redirected to the standard
	 * streams, and an exception will be thrown if the exit code is anything
	 * other than zero.
	 * 
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(String... args) throws IOException {
		admin(null, args);
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the standard streams, and
	 * an exception will be thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(File cwd, String... args) throws IOException {
		int ret = adminCommand(cwd, args);
		if (ret != 0) {
			throw new IOException("Command returned non-zero status '" + ret + "'.");
		}
		;
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the provied output stream,
	 * and an exception will be thrown if the exit code is anything other than
	 * zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(File cwd, OutputStream out, String... args) throws IOException {
		int ret = adminCommand(cwd, out, args);
		if (ret != 0) {
			throw new IOException("Command returned non-zero status '" + ret + "'.");
		}
	}

	/**
	 * Run a command as administrator. I/O will be redirected to the standard
	 * streams, and an exception will be thrown if the exit code is anything
	 * other than zero.
	 * 
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(List<String> args) throws IOException {
		admin((File) null, args);
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the standard streams, and
	 * an exception will be thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(File cwd, List<String> args) throws IOException {
		admin(cwd, null, args);
	}

	/**
	 * Run a command as an administrator. I/O will be redirected to the provied
	 * output stream, and an exception will be thrown if the exit code is
	 * anything other than zero.
	 * 
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(OutputStream out, List<String> args) throws IOException {
		admin((File) null, out, args);
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the provied output stream,
	 * and an exception will be thrown if the exit code is anything other than
	 * zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @throws IOException
	 *             on any error
	 */
	public static void admin(File cwd, OutputStream out, List<String> args) throws IOException {
		Process process = doAdminCommand(cwd, args, out);
		if (process.exitValue() != 0) {
			throw new IOException(
					"Update process exited with status " + process.exitValue() + ". See log for more details.");
		}
	}

	/**
	 * Run a command as an administrator. I/O will be redirected to the provied
	 * output stream, and the exit code will be returned.
	 * 
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommand(OutputStream out, List<String> args) throws IOException {
		return runCommand((File) null, out, (String[]) args.toArray(new String[0]));
	}

	/**
	 * Run a command as an administrator. I/O will be redirected to the standard
	 * streams, and the exit code will be returned.
	 * 
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommand(List<String> args) throws IOException {
		return runCommand((String[]) args.toArray(new String[0]));
	}

	/**
	 * Run a command as an administrator. I/O will be redirected to the standard
	 * streams, and the exit code will be returned.
	 * 
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommand(String... args) throws IOException {
		return adminCommand(null, args);
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the standard streams, and
	 * the exit code will be returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommand(File cwd, String... args) throws IOException {
		return adminCommand(cwd, System.out, args);
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the provied output stream,
	 * and the exit code will be returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param out
	 *            output stream to write to
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommand(File cwd, OutputStream out, String... args) throws IOException {
		elevate();
		try {
			return runCommand(cwd, out, args);
		} finally {
			restrict();
		}
	}

	/**
	 * Run a command as an administrator. I/O will be written to the provided
	 * file, and the exit code will be returned.
	 * 
	 * @param file
	 *            file to write to
	 * @param args
	 *            command arguments
	 * @return exit code
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommandAndOutputToFile(File file, String... args) throws IOException {
		return adminCommandAndOutputToFile(null, file, args);
	}

	/**
	 * Run a command as an administrator with a particular working directory and
	 * capture all of the output to a file. The exit code will be returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param file
	 *            file to write output to
	 * @param args
	 *            command arguments
	 * @return output as list of strings
	 * @throws IOException
	 *             on any error
	 */
	public static int adminCommandAndOutputToFile(File cwd, File file, String... args) throws IOException {
		elevate();
		try {
			return runCommandAndOutputToFile(cwd, file, args);
		} finally {
			restrict();
		}
	}

	/**
	 * Run a command as an administrator and capture all of the output to a list
	 * of strings. An exception will be thrown if the exit code is anything
	 * other than zero.
	 * 
	 * @param args
	 *            command arguments
	 * @return output as list of strings
	 * @throws IOException
	 *             on any error
	 */
	public static Collection<String> adminCommandAndCaptureOutput(String... args) throws IOException {
		return adminCommandAndCaptureOutput(null, args);
	}

	/**
	 * Run a command as an administrator with a particular working directory and
	 * capture all of the output to a list of strings. An exception will be
	 * thrown if the exit code is anything other than zero.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @return output as list of strings
	 * @throws IOException
	 *             on any error
	 */
	public static Collection<String> adminCommandAndCaptureOutput(File cwd, String... args) throws IOException {
		elevate();
		try {
			return runCommandAndCaptureOutput(cwd, args);
		} finally {
			restrict();
		}
	}

	/**
	 * Run a command. I/O will be redirected to the standard streams, and the
	 * process will be returned.
	 * 
	 * @param args
	 *            command arguments
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doCommand(List<String> args) throws IOException {
		return doCommand((File) null, args);
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the standard streams, and the process will be
	 * returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doCommand(File cwd, List<String> args) throws IOException {
		return doCommand(cwd, args, null);
	}

	/**
	 * Run a command. I/O will be redirected to the provied output stream, and
	 * the process will be returned
	 * 
	 * @param args
	 *            command arguments
	 * @param out
	 *            output stream to write to
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doCommand(List<String> args, OutputStream out) throws IOException {
		return doCommand((File) null, args, out);
	}

	/**
	 * Run a command with the working directory set to a particular location.
	 * I/O will be redirected to the provied output stream, and the process will
	 * be returned
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @param out
	 *            output stream to write to
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doCommand(File cwd, List<String> args, OutputStream out) throws IOException {
		args = new ArrayList<String>(args);

		LOG.fine("Running command: " + StringUtils.join(args, " "));
		ForkerBuilder builder = new ForkerBuilder(args);
		if (builder.io() == null)
			builder.io(io.get() == null ? IO.INPUT : io.get());
		checkElevationAndEnvironment(builder);
		if (cwd != null) {
			builder.directory(cwd);
		}
		builder.redirectErrorStream(true);
		Process process = builder.start();
		InputStream inputStream = process.getInputStream();
		try {
			if (out == null) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
				readInput(reader);
			} else {
				out = new FilterOutputStream(out) {
					@Override
					public void write(int b) throws IOException {
						super.write(b);
						System.out.print((char) b);
					}

					@Override
					public void write(byte[] b, int off, int len) throws IOException {
						super.write(b, off, len);
						System.out.print(new String(b, off, len));
					}

				};
				IOUtils.copy(inputStream, out);
			}
		} finally {
			try {
				process.waitFor();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			inputStream.close();
		}
		return process;
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the provied output stream,
	 * and the process will be returned
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @param out
	 *            output stream to write to
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doAdminCommand(File cwd, List<String> args, OutputStream out) throws IOException {
		elevate();
		try {
			return doCommand(cwd, args, out);
		} finally {
			restrict();
		}
	}

	/**
	 * Run a command as an administrator. I/O will be redirected to the standard
	 * streams, and the process will be returned.
	 * 
	 * @param args
	 *            command arguments
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doAdminCommand(List<String> args) throws IOException {
		return doAdminCommand((File) null, args);
	}

	/**
	 * Run a command as an administrator with the working directory set to a
	 * particular location. I/O will be redirected to the standard streams, and
	 * the process will be returned.
	 * 
	 * @param cwd
	 *            working directory
	 * @param args
	 *            command arguments
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doAdminCommand(File cwd, List<String> args) throws IOException {
		return doAdminCommand(cwd, args, null);
	}

	/**
	 * Run a command as an administrator. I/O will be redirected to the provied
	 * output stream, and the process will be returned
	 * 
	 * @param args
	 *            command arguments
	 * @param out
	 *            output stream to write to
	 * @return process
	 * @throws IOException
	 *             on any error
	 */
	public static Process doAdminCommand(List<String> args, OutputStream out) throws IOException {
		return doAdminCommand((File) null, args, out);
	}

	private static void checkElevationAndEnvironment(ForkerBuilder pb) {
		Map<String, String> env = environment.get();
		if (env != null && !env.isEmpty()) {
			pb.environment().putAll(env);
		}

		if (Boolean.TRUE.equals(elevated.get())) {
			if (Forker.isDaemonRunning()) {
				pb.effectiveUser(new EffectiveUserFactory.POSIXUIDEffectiveUser(0));
			} else {
				pb.effectiveUser(sudoPassword == null ? EffectiveUserFactory.getDefault().administrator()
						: new SudoFixedPasswordUser(sudoPassword));
			}
		} else {
			if (Forker.isDaemonRunningAsAdministrator())
				pb.effectiveUser(new EffectiveUserFactory.POSIXUsernameEffectiveUser(System.getProperty("user.name")));
		}
	}

	/**
	 * Determine if a a particular OS command is on the PATH.
	 * 
	 * @param command command
	 * @return command exists on path
	 */
	public static boolean hasCommand(String command) {
		if (SystemUtils.IS_OS_LINUX) {
			boolean el = OSCommand.restrict();
			try {
				Collection<String> out = OSCommand.runCommandAndCaptureOutput("which", command);
				return !out.isEmpty();
			} catch (Exception e) {
				return false;
			} finally {
				if (el)
					OSCommand.elevate();
			}
		} else {
			String path = System.getenv("PATH");
			if (path != "") {
				boolean found = false;
				for (String p : path.split(File.pathSeparator)) {
					File f = new File(p);
					if (f.isDirectory()) {
						String cmd = command;
						if (SystemUtils.IS_OS_WINDOWS) {
							cmd += ".exe";
						}
						File e = new File(f, cmd);
						if (e.exists()) {
							found = true;
							break;
						}
					}
				}
				return found;
			}
			throw new UnsupportedOperationException(System.getProperty("os.name")
					+ " is not supported. Cannot determine if command " + command + " exists");
		}
	}

	private static void readInput(BufferedReader reader) throws IOException {
		String line = null;
		while ((line = reader.readLine()) != null) {
			System.out.println(line);
		}
	}

}
