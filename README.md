Dead Code Agent
===============

A dead code agent for Java to find unused packages. Its aim is to be as understable as possible.

The idea of this agent is to collect all classes (and interfaces) that are loaded and are actually used, 
by instrumenting the static initializers. This under-approximates the amount of unused
code but should reduce the performance hit of the agent and the errors with too eager deletion.

The code should be easy to understand, the only external dependency for the agent
being [javassist](http://www.javassist.org/) for the bytecode processing
(and [picocli](https://picocli.info/) for CLI parsing).

Build
-----
```sh
# build it
mvn package
```

Agent Usage and File Format
---------------------------
```sh
# run your program and print the list of loaded (l) and used (u) classes
java -javaagent:target/dead-code.jar=output=classes.txt ...
```

This should yield a `classes.txt` file which contains one line for every class loaded or used:
```
...
l sun/launcher/LauncherHelper
l java/lang/WeakPairMap$Pair$Weak
l java/lang/WeakPairMap$WeakRefPeer
u java/lang/WeakPairMap$WeakRefPeer
u java/lang/WeakPairMap$Pair$Weak
```
Terminology:

- "loaded" means here that a class is loaded after the initiation of the dead code agent.
- "used" means that the static initializer has been called 
- (see [Java Language Specification](https://docs.oracle.com/javase/specs/jls/se17/html/jls-12.html#jls-12.4.1)).

Processor Usage
---------------

Structure
---------
- Main: Entry point of the Java agent
- Options: Parses and stores the agent options
- ClassTransformer: Modifies the classes to add static initializers

License
-------
MIT, Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
and dead code agent contributors


*This project is a prototype of the [SapMachine](https://sapmachine.io) team
at [SAP SE](https://sap.com)*