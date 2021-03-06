# Forker

Forker is a set of utilities and helpers for executing operating system commands. It can be used in a number of ways :-
 
## Forker Client
  
Forker Client provides a set of utilities, *OSCommand*, and the ProcessBuilder replacement *ForkerBuilder*. See [forker-client/README.md](forker-client/README.md) for more information.

### OSCommand

Generally this is a simply case of a single call and Forker will deal with checking the exit code and redirecting or capturing standard output and error output. OS commands can be run either as the current user or as an administrator. 
   
### ForkerBuilder 
The replacement to ProcessBuilder, ForkerBuilder uses different methods depending on the type of  I/O used, and also allows processes to be run as an administrator (or any other user). Depending on whether input, output, or I/O is needed (which should be provided as hint to the API), popen, system or a standard process will be used.
    
## Forker Daemon

The Forker Daemon can be used to reduce forking costs on Linux, by starting a separate small JVM whose job it is to just execute commands on behalf of another runtime. The daemon can be started as an administrator either on demand or up front (meaning the administrator password is only requested once), also allowing opening of administrator only files for reading and writing. The same daemon is also used by Forker Wrapper to provide communication between the wrapper and the wrapped application.

Depending on your requirements, you may not need to worry about Forker Daemon yourself as the client utilities manage it's use for you. However, if you have special requirements, you may manage the daemon yourself. See [forker-daemon/README.md](forker-daemon/README.md).

### Forker Wrapper

A 'wrapper' to execute services in Java. Similar to JSW (Java Service Wrapper) and YAJSW, Forker Wrapper can be used to launch processes in the background, track the process ID, capture output to log, automatically restart a hung or crashed JVM and more.

See [forker-wrapper/README.md](forker-wrapper/README.md)

### Pseudo Terminal Support

Execute commands and shells with a pseudo terminal (or 'pty'), providing command line editing and full interactive I/O. This is achieved using Pty4J. This could be used for example to create a Java based telnet or SSH terminal server. 

See [forker-pty/README.md](forker-pty/README.md)