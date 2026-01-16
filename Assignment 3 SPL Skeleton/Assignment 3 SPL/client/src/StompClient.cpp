#include <iostream>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <map>
#include <vector>
#include <string>
#include <sstream>
#include <fstream>
#include <algorithm>
#include "ConnectionHandler.h"
#include "event.h"

// Game Event Storage
struct GameEvent {
    std::string eventName;
    int time;
    std::string description;
    std::map<std::string, std::string> generalUpdates;
    std::map<std::string, std::string> teamAUpdates;
    std::map<std::string, std::string> teamBUpdates;
    
    // Constructor to satisfy Weffc++
    GameEvent() : eventName(""), time(0), description(""), generalUpdates(), teamAUpdates(), teamBUpdates() {}
};

struct Game {
    std::string teamA;
    std::string teamB;
    std::map<std::string, std::string> generalStats;
    std::map<std::string, std::string> teamAStats;
    std::map<std::string, std::string> teamBStats;
    std::vector<GameEvent> events;
    
    // Constructor to satisfy Weffc++
    Game() : teamA(""), teamB(""), generalStats(), teamAStats(), teamBStats(), events() {}
    Game(std::string a, std::string b) : teamA(a), teamB(b), generalStats(), teamAStats(), teamBStats(), events() {}
};

// Client State
class StompClient {
private:
    std::mutex stateMutex;
    std::condition_variable logoutCv;
    
    bool isLoggedIn;
    std::string currentUsername;
    ConnectionHandler* connection;
    
    std::map<std::string, int> subscriptions;
    int nextSubscriptionId;
    int nextReceiptId;
    int lastDisconnectReceipt;
    
    std::map<std::string, std::map<std::string, std::vector<GameEvent>>> games;
    std::map<std::string, Game> gameStats;
    
    std::atomic<bool> shouldTerminate;
    bool isLogoutProcessed;

public:
    // Initializer list fixed to match declaration order
    StompClient() : 
        stateMutex(), logoutCv(), isLoggedIn(false), currentUsername(""), connection(nullptr),
        subscriptions(), nextSubscriptionId(0), nextReceiptId(100), lastDisconnectReceipt(-1),
        games(), gameStats(), shouldTerminate(false), isLogoutProcessed(false) {}
    
    // Rule of Three: Destructor, delete copy ctor/assignment
    ~StompClient() {
        if (connection) {
            delete connection;
            connection = nullptr;
        }
    }
    StompClient(const StompClient&) = delete;
    StompClient& operator=(const StompClient&) = delete;
    
    void start() {
        std::thread keyboardThread(&StompClient::keyboardLoop, this);
        std::thread socketThread(&StompClient::socketLoop, this);
        
        keyboardThread.join();
        socketThread.join();
    }
    
    void keyboardLoop() {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (line.empty()) continue;
            processCommand(line);
            if (shouldTerminate.load()) break;
        }
    }
    
    void processCommand(const std::string& line) {
        std::istringstream iss(line);
        std::string cmd;
        iss >> cmd;
        
        try {
            if (cmd == "login") handleLogin(iss);
            else if (cmd == "join") handleJoin(iss);
            else if (cmd == "exit") handleExit(iss);
            else if (cmd == "report") handleReport(iss);
            else if (cmd == "summary") handleSummary(iss);
            else if (cmd == "logout") handleLogout();
            else std::cout << "Unknown command: " << cmd << std::endl;
        } catch (const std::exception& e) {
            std::cout << "Error: " << e.what() << std::endl;
        }
    }
    
    void handleLogin(std::istringstream& iss) {
        std::string hostPort, username, password;
        iss >> hostPort >> username >> password;
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (isLoggedIn) {
                std::cout << "The client is already logged in, log out before trying again" << std::endl;
                return;
            }
        }
        
        size_t colonPos = hostPort.find(':');
        if (colonPos == std::string::npos) {
            std::cout << "Invalid host:port format" << std::endl;
            return;
        }
        
        std::string host = hostPort.substr(0, colonPos);
        short port = (short)std::stoi(hostPort.substr(colonPos + 1));
        
        ConnectionHandler* newConnection = new ConnectionHandler(host, port);
        if (!newConnection->connect()) {
            std::cout << "Could not connect to server" << std::endl;
            delete newConnection;
            return;
        }
        
        std::string connectFrame = buildConnectFrame(username, password);
        if (!newConnection->sendFrameAscii(connectFrame, '\0')) {
             std::cout << "Could not send connect frame" << std::endl;
             delete newConnection;
             return;
        }
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            connection = newConnection;
            currentUsername = username;
        }
    }
    
    void handleJoin(std::istringstream& iss) {
        std::string gameName;
        iss >> gameName;
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!isLoggedIn) {
                std::cout << "Not logged in" << std::endl;
                return;
            }
            if (subscriptions.find(gameName) != subscriptions.end()) {
                std::cout << "Already joined " << gameName << std::endl;
                return;
            }
        }
        
        int subId = nextSubscriptionId++;
        int receiptId = nextReceiptId++;
        std::string subscribeFrame = buildSubscribeFrame(gameName, subId, receiptId);
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (connection) {
                connection->sendFrameAscii(subscribeFrame, '\0');
                subscriptions[gameName] = subId;
            }
        }
        std::cout << "Joined channel " << gameName << std::endl;
    }
    
    void handleExit(std::istringstream& iss) {
        std::string gameName;
        iss >> gameName;
        
        int subId;
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!isLoggedIn) {
                std::cout << "Not logged in" << std::endl;
                return;
            }
            auto it = subscriptions.find(gameName);
            if (it == subscriptions.end()) {
                std::cout << "Not subscribed to " << gameName << std::endl;
                return;
            }
            subId = it->second;
        }
        
        int receiptId = nextReceiptId++;
        std::string unsubscribeFrame = buildUnsubscribeFrame(subId, receiptId);
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (connection) {
                connection->sendFrameAscii(unsubscribeFrame, '\0');
                subscriptions.erase(gameName);
            }
        }
        std::cout << "Exited channel " << gameName << std::endl;
    }
    
    void handleReport(std::istringstream& iss) {
        std::string filePath;
        iss >> filePath;
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!isLoggedIn) {
                std::cout << "Not logged in" << std::endl;
                return;
            }
        }
        
        try {
            auto nne = parseEventsFile(filePath);
            std::string gameName = nne.team_a + "_" + nne.team_b;
            
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                if (gameStats.find(gameName) == gameStats.end()) {
                    gameStats[gameName] = Game(nne.team_a, nne.team_b);
                }
            }
            
            for (const auto& event : nne.events) {
                std::string sendFrame = buildSendFrame(gameName, nne.team_a, nne.team_b, event);
                
                {
                    std::lock_guard<std::mutex> lock(stateMutex);
                    if (connection) {
                        connection->sendFrameAscii(sendFrame, '\0');
                        
                        GameEvent ge;
                        ge.eventName = event.event_name;
                        ge.time = event.time;
                        ge.description = event.description;
                        ge.generalUpdates = event.general_updates;
                        ge.teamAUpdates = event.team_a_updates;
                        ge.teamBUpdates = event.team_b_updates;
                        
                        games[gameName][currentUsername].push_back(ge);
                    }
                }
            }
        } catch (const std::exception& e) {
            std::cout << "Error reading file: " << e.what() << std::endl;
        }
    }
    
    void handleSummary(std::istringstream& iss) {
        std::string gameName, username, outputFile;
        iss >> gameName >> username >> outputFile;
        
        std::vector<GameEvent> events;
        Game game;
        bool found = false;
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!isLoggedIn) {
                std::cout << "Not logged in" << std::endl;
                return;
            }
            if (games.find(gameName) == games.end() || games[gameName].find(username) == games[gameName].end()) {
                std::cout << "No events found for " << username << " in " << gameName << std::endl;
                return;
            }
            events = games[gameName][username];
            if (gameStats.find(gameName) != gameStats.end()) {
                game = gameStats[gameName];
                found = true;
            }
        }
        
        if (!found) {
            std::cout << "No game data found" << std::endl;
            return;
        }
        
        std::stable_sort(events.begin(), events.end(), [](const GameEvent& a, const GameEvent& b) {
            return a.time < b.time;
        });
        
        std::ofstream outFile(outputFile);
        if (!outFile.is_open()) {
            std::cout << "Error: Could not open file " << outputFile << std::endl;
            return;
        }
        
        outFile << game.teamA << " vs " << game.teamB << std::endl;
        outFile << "Game stats:" << std::endl;
        
        std::vector<std::pair<std::string, std::string>> sortedGeneralStats(game.generalStats.begin(), game.generalStats.end());
        std::sort(sortedGeneralStats.begin(), sortedGeneralStats.end());
        outFile << "General stats:" << std::endl;
        for (const auto& stat : sortedGeneralStats) outFile << stat.first << ": " << stat.second << std::endl;
        
        std::vector<std::pair<std::string, std::string>> sortedTeamAStats(game.teamAStats.begin(), game.teamAStats.end());
        std::sort(sortedTeamAStats.begin(), sortedTeamAStats.end());
        outFile << game.teamA << " stats:" << std::endl;
        for (const auto& stat : sortedTeamAStats) outFile << stat.first << ": " << stat.second << std::endl;
        
        std::vector<std::pair<std::string, std::string>> sortedTeamBStats(game.teamBStats.begin(), game.teamBStats.end());
        std::sort(sortedTeamBStats.begin(), sortedTeamBStats.end());
        outFile << game.teamB << " stats:" << std::endl;
        for (const auto& stat : sortedTeamBStats) outFile << stat.first << ": " << stat.second << std::endl;
        
        outFile << "Game event reports:" << std::endl;
        for (const auto& event : events) {
            outFile << event.time << " - " << event.eventName << ":" << std::endl;
            outFile << event.description << std::endl;
        }
        outFile.close();
        std::cout << "Summary written to " << outputFile << std::endl;
    }
    
    void handleLogout() {
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!isLoggedIn) {
                std::cout << "Not logged in" << std::endl;
                return;
            }
            if (!connection) {
                std::cout << "No active connection" << std::endl;
                return;
            }
        }
        
        int receipt = nextReceiptId++;
        lastDisconnectReceipt = receipt;
        std::string frame = buildDisconnectFrame(receipt);
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            connection->sendFrameAscii(frame, '\0');
            isLogoutProcessed = false;
        }
        
        {
            std::unique_lock<std::mutex> lock(stateMutex);
            logoutCv.wait_for(lock, std::chrono::seconds(5), [this] { return isLogoutProcessed; });
        }
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (connection) {
                connection->close();
                delete connection;
                connection = nullptr;
            }
            isLoggedIn = false;
            subscriptions.clear();
        }
        shouldTerminate.store(true);
    }
    
    void socketLoop() {
        while (!shouldTerminate.load()) {
            ConnectionHandler* conn = nullptr;
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                conn = connection;
            }
            if (!conn) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
            
            std::string frame;
            if (!conn->getFrameAscii(frame, '\0')) {
                {
                    std::lock_guard<std::mutex> lock(stateMutex);
                    if (connection) {
                        connection->close();
                        delete connection;
                        connection = nullptr;
                        isLoggedIn = false;
                    }
                }
                break;
            }
            processFrame(frame);
        }
    }
    
    void processFrame(const std::string& frameStr) {
        std::istringstream iss(frameStr);
        std::string line;
        if (!std::getline(iss, line)) return;
        
        line.erase(0, line.find_first_not_of(" \t"));
        line.erase(line.find_last_not_of(" \t") + 1);
        
        if (line == "CONNECTED") handleConnectedFrame();
        else if (line == "MESSAGE") handleMessageFrame(frameStr);
        else if (line == "RECEIPT") handleReceiptFrame(frameStr);
        else if (line == "ERROR") handleErrorFrame(frameStr);
    }
    
    void handleConnectedFrame() {
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            isLoggedIn = true;
        }
        std::cout << "Login successful" << std::endl;
    }
    
    void handleMessageFrame(const std::string& frameStr) {
        std::istringstream iss(frameStr);
        std::string line;
        std::map<std::string, std::string> headers;
        std::getline(iss, line); 
        
        while (std::getline(iss, line) && !line.empty()) {
            trimString(line);
            if (line.empty()) break;
            size_t colonPos = line.find(':');
            if (colonPos != std::string::npos) {
                std::string key = line.substr(0, colonPos);
                std::string value = line.substr(colonPos + 1);
                trimString(key);
                trimString(value);
                headers[key] = value;
            }
        }
        
        std::string destination = headers["destination"];
        if (!destination.empty() && destination[0] == '/') destination = destination.substr(1);
        
        std::string bodyLine;
        std::map<std::string, std::string> updates;
        while (std::getline(iss, bodyLine)) {
            trimString(bodyLine);
            if (bodyLine.empty()) continue;
            size_t colonPos = bodyLine.find(':');
            if (colonPos != std::string::npos) {
                std::string key = bodyLine.substr(0, colonPos);
                std::string value = bodyLine.substr(colonPos + 1);
                trimString(key);
                trimString(value);
                updates[key] = value;
            }
        }
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (gameStats.find(destination) == gameStats.end()) {
                gameStats[destination] = Game(updates["team a"], updates["team b"]);
            }
            Game& game = gameStats[destination];
            for (const auto& upd : updates) {
                if (upd.first != "team a" && upd.first != "team b" &&
                    upd.first != "user" && upd.first != "event name" &&
                    upd.first != "time" && upd.first != "description" &&
                    upd.first != "destination") {
                    game.generalStats[upd.first] = upd.second;
                }
            }
        }
    }
    
    void handleReceiptFrame(const std::string& frameStr) {
        std::istringstream iss(frameStr);
        std::string line;
        int receiptId = -1;
        std::getline(iss, line); 
        
        while (std::getline(iss, line)) {
            trimString(line);
            if (line.find("receipt-id") != std::string::npos) {
                size_t colonPos = line.find(':');
                if (colonPos != std::string::npos) {
                    std::string idStr = line.substr(colonPos + 1);
                    trimString(idStr);
                    try { receiptId = std::stoi(idStr); } catch (...) {}
                }
                break;
            }
        }
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (receiptId == lastDisconnectReceipt) isLogoutProcessed = true;
        }
        logoutCv.notify_all();
    }
    
    void handleErrorFrame(const std::string& frameStr) {
        std::istringstream iss(frameStr);
        std::string line;
        std::string errorMsg;
        std::getline(iss, line); 
        
        while (std::getline(iss, line)) {
            trimString(line);
            if (line.find("message:") != std::string::npos) {
                errorMsg = line.substr(line.find(":") + 1);
                trimString(errorMsg);
                break;
            }
        }
        
        if (errorMsg.empty()) {
            std::string bodyLine;
            while (std::getline(iss, bodyLine)) {
                if (!bodyLine.empty()) { errorMsg = bodyLine; break; }
            }
        }
        std::cout << "Error: " << errorMsg << std::endl;
        
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (connection) {
                connection->close();
                delete connection;
                connection = nullptr;
                isLoggedIn = false;
            }
        }
    }
    
    std::string buildConnectFrame(const std::string& username, const std::string& password) {
        std::string frame = "CONNECT\n";
        frame += "accept-version:1.2\n";
        frame += "host:stomp.cs.bgu.ac.il\n";
        frame += "login:" + username + "\n";
        frame += "passcode:" + password + "\n";
        frame += "\n"; 
        return frame;
    }
    
    std::string buildSubscribeFrame(const std::string& gameName, int subId, int receiptId) {
        std::string frame = "SUBSCRIBE\n";
        frame += "destination:/" + gameName + "\n";
        frame += "id:" + std::to_string(subId) + "\n";
        frame += "receipt:" + std::to_string(receiptId) + "\n";
        frame += "\n";
        return frame;
    }
    
    std::string buildUnsubscribeFrame(int subId, int receiptId) {
        std::string frame = "UNSUBSCRIBE\n";
        frame += "id:" + std::to_string(subId) + "\n";
        frame += "receipt:" + std::to_string(receiptId) + "\n";
        frame += "\n";
        return frame;
    }
    
    std::string buildSendFrame(const std::string& gameName, const std::string& teamA,
                               const std::string& teamB, const Event& event) {
        std::string frame = "SEND\n";
        frame += "destination:/" + gameName + "\n";
        frame += "user:" + currentUsername + "\n";
        frame += "team a:" + teamA + "\n";
        frame += "team b:" + teamB + "\n";
        frame += "event name:" + event.event_name + "\n";
        frame += "time:" + std::to_string(event.time) + "\n";
        
        frame += "general game updates:\n";
        for (const auto& upd : event.general_updates) frame += upd.first + ":" + upd.second + "\n";
        
        frame += "team a updates:\n";
        for (const auto& upd : event.team_a_updates) frame += upd.first + ":" + upd.second + "\n";
        
        frame += "team b updates:\n";
        for (const auto& upd : event.team_b_updates) frame += upd.first + ":" + upd.second + "\n";
        
        frame += "description:\n" + event.description + "\n";
        return frame;
    }
    
    std::string buildDisconnectFrame(int receiptId) {
        std::string frame = "DISCONNECT\n";
        frame += "receipt:" + std::to_string(receiptId) + "\n";
        frame += "\n";
        return frame;
    }
    
    void trimString(std::string& str) {
        if(str.empty()) return;
        str.erase(0, str.find_first_not_of(" \t\r\n"));
        str.erase(str.find_last_not_of(" \t\r\n") + 1);
    }
};

int main(int argc, char* argv[]) {
    StompClient client;
    client.start();
    return 0;
}