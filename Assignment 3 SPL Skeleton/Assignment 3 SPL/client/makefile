CXX := g++
CFLAGS := -c -Wall -Weffc++ -g -std=c++11 -Iinclude
LDFLAGS := -lpthread -lboost_system

all: StompWCIClient

test: bin/StompTests
	./bin/StompTests

StompWCIClient: bin/ConnectionHandler.o bin/StompClient.o bin/StompProtocol.o bin/event.o
	$(CXX) -o bin/StompWCIClient bin/ConnectionHandler.o bin/StompClient.o bin/StompProtocol.o bin/event.o $(LDFLAGS)

EchoClient: bin/ConnectionHandler.o bin/echoClient.o
	$(CXX) -o bin/EchoClient bin/ConnectionHandler.o bin/echoClient.o $(LDFLAGS)


bin/ConnectionHandler.o: src/ConnectionHandler.cpp
	$(CXX) $(CFLAGS) -o bin/ConnectionHandler.o src/ConnectionHandler.cpp

bin/StompClient.o: src/StompClient.cpp
	$(CXX) $(CFLAGS) -o bin/StompClient.o src/StompClient.cpp

bin/StompProtocol.o: src/StompProtocol.cpp
	$(CXX) $(CFLAGS) -o bin/StompProtocol.o src/StompProtocol.cpp

bin/event.o: src/event.cpp
	$(CXX) $(CFLAGS) -o bin/event.o src/event.cpp

bin/StompProtocolTests.o: tests/StompProtocolTests.cpp
	$(CXX) $(CFLAGS) -o bin/StompProtocolTests.o tests/StompProtocolTests.cpp

bin/StompTests: bin/StompProtocolTests.o bin/StompProtocol.o bin/event.o
	$(CXX) -o bin/StompTests bin/StompProtocolTests.o bin/StompProtocol.o bin/event.o $(LDFLAGS)

bin/echoClient.o: src/echoClient.cpp
	$(CXX) $(CFLAGS) -o bin/echoClient.o src/echoClient.cpp

.PHONY: clean
clean:
	rm -f bin/*