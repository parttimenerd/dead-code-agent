Dead Code Agent
===============

A dead code finder and processor for Java to find unused classes.

The idea of this agent and instrumenter is to collect all classes (and interfaces)
that are loaded and are actually used, by instrumenting the static initializers.
This under-approximates the amount of unused code
but should reduce the performance hit of the agent and the errors with too eager deletion.

The code should be easy to understand, the only external dependency for the agent
being [javassist](http://www.javassist.org/) for the bytecode processing
(and [picocli](https://picocli.info/) for CLI parsing).

You can find more detailed information in the accompanying blog post:
[Instrumenting Java Code to Find and Handle Unused Classes](https://mostlynerdless.de/blog/2023/04/06/instrumenting-java-code-to-find-and-handle-unused-classes/)(opens in new tab))

Build
-----
```sh
# build it
git clone https://github.com/parttimenerd/dead-code-agent
cd dead-code-agent
mvn package

# and as demo application the spring petclinic
git clone https://github.com/spring-projects/spring-petclinic
cd spring-petclinic
mvn package
# make the following examples more concise
cp spring-petclinic/target/spring-petclinic-3.0.0-SNAPSHOT.jar \
   petclinic.jar
```

Agent Usage and File Format
---------------------------
```sh
# run your program and print the list of loaded and not used (l) and used (u) classes
java -javaagent:./target/dead-code.jar=output=classes.txt \
     -jar petclinic.jar
```

This should yield a `classes.txt` file which contains one line for every class loaded or used:
```
...
u ch.qos.logback.classic.encoder.PatternLayoutEncoder
l ch.qos.logback.classic.joran.JoranConfigurator
u ch.qos.logback.classic.jul.JULHelper
u ch.qos.logback.classic.jul.LevelChangePropagator
```
Terminology:

- "loaded" means here that a class is loaded after the initiation of the dead code agent
- "used" means that the static initializer has been called 
- (see [Java Language Specification](https://docs.oracle.com/javase/specs/jls/se17/html/jls-12.html#jls-12.4.1)).

Instrumenter Usage
---------------
You can also create an instrumented version of your JAR. This is slightly better than the agent
as it seems to be more reliable with Spring.
```sh
java -jar target/dead-code.jar classes.txt \
          instrument petclinic.jar instrumented.jar
```

To create a JAR that logs usages of all classes deemed unused before, you can use the following:
```sh
java -jar target/dead-code.jar classes.txt \
          instrumentUnusedClasses petclinic.jar logging.jar
```

This will log the usage of all classes not marked as used in classes.txt on standard error,
or exit the program if you pass the `--exit` option to the Instrumenter.

If you, for example, recorded the used classes of a run where
you did not access the petclinic on `localhost:8080`,
then executing the modified logging.jar and accessing the petclinic results in output like:

```
Class org.apache.tomcat.util.net.SocketBufferHandler is used which is not allowed
Class org.apache.tomcat.util.net.SocketBufferHandler$1 is used which is not allowed
Class org.apache.tomcat.util.net.NioChannel is used which is not allowed
Class org.apache.tomcat.util.net.NioChannel$1 is used which is not allowed
...
```

The Instrumenter has a few more options (via `java -jar dead-code.jar --help`):

```
Usage: dead-code [-hV] <input> [COMMAND]
Process the information gathered by the dead-code agent
      <input>     The input store file
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  allClasses               List all classes
  instrument               Instrument all classes to store information about
                             which classes are loaded and used
  instrumentUnusedClasses  Instrument all unused classes to add an alert (or
                             System.exit(1) depending on the options)
  loadedClasses            List all loaded classes
  loadedLibraries          List all loaded libraries
  notLoadedLibraries       List all libraries that are present but are not
                             loaded
  reduceJAR                Remove all unused classes and libraries from the JAR
  unusedClasses            List all unused (but loaded or present in JAR)
                             classes
  unusedLibraries          List all unused libraries
  usedClasses              List all used classes
  usedLibraries            List all used libraries
```

License
-------
MIT, Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
and dead code agent contributors


*This project is a prototype of the [SapMachine](https://sapmachine.io) team
at [SAP SE](https://sap.com)*
