#include <iostream>
#include <thread>
#include <string>
#include <vector>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"

void socketTask(ConnectionHandler* handler, StompProtocol& protocol) {
    while (!protocol.isTerminated()) {
        std::string frame;
        if (!handler->getFrameAscii(frame, '\0')) {
            if (!protocol.isTerminated()) {
                std::cout << "Disconnected from server. Exiting..." << std::endl;
                protocol.markConnectionClosed();
            }
            break;
        }

        if (!frame.empty()) {
            protocol.processResponse(frame);
        }
    }
}

int main(int argc, char *argv[]) {
    StompProtocol protocol;
    ConnectionHandler* handler = nullptr;

    while (true) {
        std::string line;
        if (!std::getline(std::cin, line)) break;
        if (line.empty()) continue;

        if (handler == nullptr) {
            std::vector<std::string> words = protocol.split(line, ' ');
            if (words[0] != "login") {
                std::cout << "Please login first." << std::endl;
                continue;
            }

            std::string hostPort = words[1];
            size_t colonPos = hostPort.find(':');
            std::string host = hostPort.substr(0, colonPos);
            short port = std::stoi(hostPort.substr(colonPos + 1));

            handler = new ConnectionHandler(host, port);
            if (!handler->connect()) {
                std::cout << "Could not connect to server" << std::endl;
                delete handler;
                handler = nullptr;
                continue;
            }

            std::string connectFrame = protocol.processInput(line);
            handler->sendFrameAscii(connectFrame, '\0');

            std::thread readerThread(socketTask, handler, std::ref(protocol));

            while (!protocol.isTerminated()) {
                std::string input;
                if (!std::getline(std::cin, input)) break;
                
                std::string stompFrame = protocol.processInput(input);
                if (!stompFrame.empty()) {
                    size_t start = 0;
                    size_t end = stompFrame.find('\0');
                    while (end != std::string::npos) {
                        std::string singleFrame = stompFrame.substr(start, end - start);
                        handler->sendFrameAscii(singleFrame, '\0');
                        start = end + 1;
                        end = stompFrame.find('\0', start);
                    }
                    if (start < stompFrame.length()) {
                        handler->sendFrameAscii(stompFrame.substr(start), '\0');
                    }
                }
            }

            if (readerThread.joinable()) readerThread.join();
            protocol.resetAfterSession();
            delete handler;
            handler = nullptr;
            
            std::cout << "You are now logged out." << std::endl;
        }
    }

    return 0;
}