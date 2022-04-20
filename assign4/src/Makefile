JFLAGS = -g
JC = javac

.SUFFIXES: .java .class

.java.class:
	$(JC) $(JFLAGS) $*.java

CLASSES = \
	TCPend.java\
	Sender.java\
	Receiver.java

default: classes

classes: $(CLASSES:.java=.class)

clean:
	$(RM) *.class
