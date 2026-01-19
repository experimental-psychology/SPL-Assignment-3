#pragma once
#include <string>
#include <map>
#include <vector>
#include <atomic>
#include <mutex>
#include "../include/ConnectionHandler.h"
#include "../include/event.h" 

class StompProtocol {
private:
    std::string currentUsername; 
    std::atomic<int> subscriptionCounter;
    std::atomic<int> receiptCounter;
    mutable std::mutex _mutex;
    std::map<int, std::string> subIdToCanonical;
    std::map<std::string, int> canonicalToSubId; 
    std::map<int, std::string> receiptIdToCommand;
    std::map<std::string, std::string> canonicalToDestination;

    std::map<std::string, std::vector<Event>> gameReports;
    
    bool shouldTerminate;

    static std::string trim(const std::string& value);
    static std::string normalizeGameName(const std::string& raw);
    std::string resolveDestinationForCanonical(const std::string& canonical) const;
    void ensureSummaryFile(std::ofstream& outFile, const std::string& teamA, const std::string& teamB,
                           const std::vector<Event>& userEvents);
    void storeEvent(const std::string& canonicalGame, const Event& event);

public:
    StompProtocol();
    
    std::vector<std::string> split(const std::string& str, char delimiter);

    std::string processInput(std::string input);
    void processResponse(std::string frame);
    bool isTerminated() const;
    void markConnectionClosed();
    void resetAfterSession();
};