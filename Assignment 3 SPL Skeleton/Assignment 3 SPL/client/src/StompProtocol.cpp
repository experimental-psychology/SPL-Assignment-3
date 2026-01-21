#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <sstream>
#include <iostream>
#include <fstream>
#include <algorithm>
#include <chrono>
#include <thread>
#include <cctype>

StompProtocol::StompProtocol() : 
    currentUsername(""), 
    subscriptionCounter(0), 
    receiptCounter(0), 
    subIdToCanonical(), 
    canonicalToSubId(), 
    receiptIdToCommand(), 
    canonicalToDestination(),
    gameReports(), 
    shouldTerminate(false) {}

std::string StompProtocol::trim(const std::string& value) {
    size_t start = 0;
    size_t end = value.size();
    while (start < end && std::isspace(static_cast<unsigned char>(value[start]))) {
        ++start;
    }
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(start, end - start);
}

std::string StompProtocol::normalizeGameName(const std::string& raw) {
    std::string cleaned = trim(raw);
    std::string canonical;
    canonical.reserve(cleaned.size());
    bool lastUnderscore = false;
    for (char c : cleaned) {
        unsigned char uc = static_cast<unsigned char>(c);
        if (std::isalnum(uc)) {
            canonical.push_back(static_cast<char>(std::tolower(uc)));
            lastUnderscore = false;
        } else if (c == '_' || c == '-' || c == ' ' || c == '/') {
            if (!lastUnderscore && !canonical.empty()) {
                canonical.push_back('_');
                lastUnderscore = true;
            }
        }
    }
    while (!canonical.empty() && canonical.back() == '_') {
        canonical.pop_back();
    }
    return canonical;
}

static std::string canonicalToPretty(const std::string& canonical) {
    std::string pretty;
    pretty.reserve(canonical.size());
    bool capitalize = true;
    for (char c : canonical) {
        if (c == '_') {
            pretty.push_back('_');
            capitalize = true;
            continue;
        }
        unsigned char uc = static_cast<unsigned char>(c);
        if (capitalize) {
            pretty.push_back(static_cast<char>(std::toupper(uc)));
            capitalize = false;
        } else {
            pretty.push_back(static_cast<char>(std::tolower(uc)));
        }
    }
    return pretty;
}

std::string StompProtocol::resolveDestinationForCanonical(const std::string& canonical) const {
    auto it = canonicalToDestination.find(canonical);
    if (it != canonicalToDestination.end() && !it->second.empty()) {
        return it->second;
    }
    return canonicalToPretty(canonical);
}

void StompProtocol::storeEvent(const std::string& canonicalGame, const Event& event) {
    std::string ownerKey = canonicalOwner(event);
    auto& eventsForUser = gameReports[canonicalGame][ownerKey];

    auto existing = std::find_if(eventsForUser.begin(), eventsForUser.end(), [&](const Event& current) {
        return current.get_time() == event.get_time() && current.get_name() == event.get_name();
    });

    if (existing != eventsForUser.end()) {
        *existing = event;
    } else {
        eventsForUser.push_back(event);
    }
}

std::string StompProtocol::canonicalOwner(const Event& event) {
    const std::string& owner = event.get_event_owner();
    return owner.empty() ? "unknown" : owner;
}

std::size_t StompProtocol::eventDetailScore(const Event& event) {
    std::size_t score = 0;
    score += event.get_game_updates().size();
    score += event.get_team_a_updates().size();
    score += event.get_team_b_updates().size();
    if (!event.get_description().empty()) score += event.get_description().size();
    if (!event.get_name().empty()) score += 1;
    return score;
}

bool StompProtocol::timelineHasRequiredEvents(const std::vector<Event>& events) {
    bool hasKickoff = false;
    bool hasHalftime = false;
    bool hasGoal = false;
    bool hasFinalWhistle = false;

    for (const Event& event : events) {
        std::string lowered;
        lowered.reserve(event.get_name().size());
        for (char c : event.get_name()) {
            lowered.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
        }

        if (!hasKickoff && lowered.find("kickoff") != std::string::npos) hasKickoff = true;
        if (!hasHalftime && lowered.find("halftime") != std::string::npos) hasHalftime = true;
        if (!hasGoal && lowered.find("goal") != std::string::npos) hasGoal = true;
        if (!hasFinalWhistle && lowered.find("final whistle") != std::string::npos) hasFinalWhistle = true;
    }

    return hasKickoff && hasHalftime && hasGoal && hasFinalWhistle;
}

std::vector<Event> StompProtocol::selectTimelineForSummary(const std::string& canonicalGame,
                                                           const std::string& targetUser) const {
    std::vector<Event> chosen;
    auto gameIt = gameReports.find(canonicalGame);
    if (gameIt == gameReports.end()) return chosen;

    auto userIt = gameIt->second.find(targetUser);
    if (userIt != gameIt->second.end()) {
        chosen = userIt->second;
        if (timelineHasRequiredEvents(chosen)) return chosen;
    }

    std::size_t bestScore = 0;
    for (const auto& ownerEntry : gameIt->second) {
        const std::string& owner = ownerEntry.first;
        const std::vector<Event>& events = ownerEntry.second;
        if (owner == targetUser) continue;
        if (!timelineHasRequiredEvents(events)) continue;

        std::size_t score = 0;
        for (const Event& event : events) {
            score += eventDetailScore(event);
        }

        if (score > bestScore) {
            bestScore = score;
            chosen = events;
        }
    }

    if (!chosen.empty()) return chosen;

    if (userIt != gameIt->second.end()) return userIt->second;

    return chosen;
}

void StompProtocol::ensureSummaryFile(std::ofstream& outFile,
                                      const std::string& teamA,
                                      const std::string& teamB,
                                      const std::vector<Event>& userEvents) {
    std::map<std::string, std::string> generalStats;
    std::map<std::string, std::string> teamAStats;
    std::map<std::string, std::string> teamBStats;

    for (const Event& e : userEvents) {
        for (const auto& entry : e.get_game_updates()) {
            generalStats[entry.first] = entry.second;
        }
        for (const auto& entry : e.get_team_a_updates()) {
            teamAStats[entry.first] = entry.second;
        }
        for (const auto& entry : e.get_team_b_updates()) {
            teamBStats[entry.first] = entry.second;
        }
    }

    outFile << teamA << " vs " << teamB << "\n";
    outFile << "Game stats:\n";

    outFile << "General stats:\n";
    for (const auto& entry : generalStats) {
        outFile << entry.first << ": " << entry.second << "\n";
    }

    outFile << teamA << " stats:\n";
    for (const auto& entry : teamAStats) {
        outFile << entry.first << ": " << entry.second << "\n";
    }

    outFile << teamB << " stats:\n";
    for (const auto& entry : teamBStats) {
        outFile << entry.first << ": " << entry.second << "\n";
    }

    outFile << "Game event reports:\n";
    for (const Event& e : userEvents) {
        outFile << e.get_time() << " - " << e.get_name() << ":\n\n";

        std::string description = e.get_description();
        while (!description.empty() && (description.back() == '\n' || description.back() == '\r')) {
            description.pop_back();
        }
        outFile << description << "\n\n";
    }
}

    std::vector<std::string> StompProtocol::split(const std::string& str, char delimiter) {
    std::vector<std::string> tokens;
    std::string token;
    std::istringstream tokenStream(str);
    while (std::getline(tokenStream, token, delimiter)) {
        tokens.push_back(token);
    }
    return tokens;
}
std::string StompProtocol::processInput(std::string input) {
    std::lock_guard<std::mutex> lock(_mutex);
    std::vector<std::string> words = split(input, ' ');
    if (words.empty()) return "";

    std::string command = words[0];
    if (command != "login" && currentUsername == "") {
        std::cout << "Error: You must login before performing any other action." << std::endl;
        return ""; 
    }
    if (command == "login") {
        if (words.size() < 4) {
            std::cout << "Usage: login <host:port> <username> <password>" << std::endl;
            return "";
        }
        if (!currentUsername.empty()) {
            std::cout << "Error: You are already logged in as " << currentUsername << std::endl;
            return "";
        }
        shouldTerminate = false;
        currentUsername = words[2];
        subscriptionCounter = 0;
        receiptCounter = 0;
        canonicalToSubId.clear();
        subIdToCanonical.clear();
        canonicalToDestination.clear();
        std::string frame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:" + words[2] + "\npasscode:" + words[3] + "\n\n";
        return frame;
    }

    if (command == "join") {
        if (words.size() < 2) {
            std::cout << "Usage: join <game>" << std::endl;
            return "";
        }
        std::string rawGame = trim(words[1]);
        std::string canonical = normalizeGameName(rawGame);
        if (canonical.empty()) {
            std::cout << "Error: Invalid game name." << std::endl;
            return "";
        }
        if (canonicalToSubId.count(canonical) != 0) {
            std::cout << "Error: Already subscribed to " << resolveDestinationForCanonical(canonical) << std::endl;
            return "";
        }
        int subId = subscriptionCounter++;
        int recId = receiptCounter++;
        std::string destination = rawGame.empty() ? resolveDestinationForCanonical(canonical) : rawGame;

        subIdToCanonical[subId] = canonical;
        canonicalToSubId[canonical] = subId;
        canonicalToDestination[canonical] = destination;
        receiptIdToCommand[recId] = "Joined channel " + destination;

        std::string frame = "SUBSCRIBE\ndestination:/" + destination + "\nid:" + std::to_string(subId) + "\nreceipt:" + std::to_string(recId) + "\n\n";
        return frame;
    }

    if (command == "exit") {
        if (words.size() > 1) {
            std::string canonical = normalizeGameName(words[1]);
            auto it = canonicalToSubId.find(canonical);
            if (it == canonicalToSubId.end()) {
                std::cout << "Error: You are not subscribed to channel " << words[1] << std::endl;
                return "";
            }

            int subId = it->second;
            int recId = receiptCounter++;
            std::string destination = resolveDestinationForCanonical(canonical);
            receiptIdToCommand[recId] = "Exited channel " + destination;

            canonicalToSubId.erase(it);
            canonicalToDestination.erase(canonical);
            subIdToCanonical.erase(subId);
            std::string frame = "UNSUBSCRIBE\nid:" + std::to_string(subId) + "\nreceipt:" + std::to_string(recId) + "\n\n";
            return frame;
        } else {
            int recId = receiptCounter++;
            receiptIdToCommand[recId] = "logout";
            shouldTerminate = true;
            currentUsername.clear();
            subIdToCanonical.clear();
            canonicalToSubId.clear();
            canonicalToDestination.clear();
            return "DISCONNECT\nreceipt:" + std::to_string(recId) + "\n\n";
        }
    }

    if (command == "logout") {
        int recId = receiptCounter++;
        receiptIdToCommand[recId] = "logout";
        shouldTerminate = true;
        currentUsername.clear();
        subIdToCanonical.clear();
        canonicalToSubId.clear();
        canonicalToDestination.clear();
        return "DISCONNECT\nreceipt:" + std::to_string(recId) + "\n\n";
    }
    if (command == "report") {
        if (words.size() < 2) {
            std::cout << "Usage: report <path/to/events.json>" << std::endl;
            return "";
        }

        names_and_events n_e;
        try {
            n_e = parseEventsFile(words[1]);
        } catch (const std::exception& ex) {
            std::cout << "Error: " << ex.what() << std::endl;
            return "";
        }

        std::string canonicalGame = normalizeGameName(n_e.team_a_name + "_" + n_e.team_b_name);
        if (canonicalGame.empty()) {
            std::cout << "Error: Could not determine game name from report." << std::endl;
            return "";
        }

        if (canonicalToSubId.count(canonicalGame) == 0) {
            std::cout << "Error: You must join " << resolveDestinationForCanonical(canonicalGame)
                      << " before reporting events." << std::endl;
            return "";
        }

        std::string destination = resolveDestinationForCanonical(canonicalGame);
        std::string allFrames = "";

        gameReports[canonicalGame][currentUsername].clear();
        
        for (const Event& e : n_e.events) {
            Event localCopy = e;
            localCopy.set_event_owner(currentUsername);
            storeEvent(canonicalGame, localCopy);
            allFrames += "SEND\ndestination:/" + destination + "\n\n";
            allFrames += "user:" + currentUsername + "\n";
            allFrames += "team a:" + e.get_team_a_name() + "\n";
            allFrames += "team b:" + e.get_team_b_name() + "\n";
            allFrames += "event name:" + e.get_name() + "\n";
            allFrames += "time:" + std::to_string(e.get_time()) + "\n";
            allFrames += "general game updates:\n";
            for (auto const& entry : e.get_game_updates()) {
                allFrames += "    " + entry.first + ":" + entry.second + "\n";
            }
            allFrames += "team a updates:\n";
            for (auto const& entry : e.get_team_a_updates()) {
                allFrames += "    " + entry.first + ":" + entry.second + "\n";
            }
            
            allFrames += "team b updates:\n";
            for (auto const& entry : e.get_team_b_updates()) {
                allFrames += "    " + entry.first + ":" + entry.second + "\n";
            }
            
            allFrames += "description:\n" + e.get_description() + "\n";
            allFrames += '\0'; 
        }
        std::cout << "Events reported successfully" << std::endl;
        return allFrames;
    }
    if (command == "summary") {
        if (words.size() < 4) {
            std::cout << "Usage: summary <game> <user> <output-file>" << std::endl;
            return "";
        }

        std::string canonical = normalizeGameName(words[1]);
        std::string targetUser = words[2];
        std::string filePath = words[3];

        std::ofstream outFile(filePath);
        if (!outFile.is_open()) {
            std::cout << "Error: Could not open file " << filePath << std::endl;
            return "";
        }

        std::vector<Event> timeline = selectTimelineForSummary(canonical, targetUser);

        if (timeline.empty()) {
            outFile << "No events found for user " << targetUser << " in game " << words[1] << "\n";
            outFile.close();
            std::cout << "Summary written to " << filePath << std::endl;
            return "";
        }

        std::sort(timeline.begin(), timeline.end(), [](const Event& a, const Event& b) {
            if (a.get_time() != b.get_time()) return a.get_time() < b.get_time();
            return a.get_name() < b.get_name();
        });

        if (!timelineHasRequiredEvents(timeline)) {
            outFile.close();
            std::cout << "Summary for " << resolveDestinationForCanonical(canonical)
                      << " is not ready yet. Waiting for additional events." << std::endl;
            return "";
        }

        ensureSummaryFile(outFile, timeline.front().get_team_a_name(), timeline.front().get_team_b_name(), timeline);
        outFile.close();
        std::cout << "Summary written to " << filePath << std::endl;
        return "";
    }
    return "";
}
void StompProtocol::processResponse(std::string frame) {
    std::lock_guard<std::mutex> lock(_mutex);
    std::vector<std::string> lines = split(frame, '\n');
    if (lines.empty()) return;

    std::string stompCommand = lines[0];

    if (stompCommand == "CONNECTED") {
        std::cout << "Login successful" << std::endl;
    }
    else if (stompCommand == "RECEIPT") {
        for (const std::string& rawLine : lines) {
            if (rawLine.find("receipt-id:") == 0) {
                int rId = std::stoi(rawLine.substr(11));
                auto msgIt = receiptIdToCommand.find(rId);
                if (msgIt != receiptIdToCommand.end()) {
                    std::cout << msgIt->second << std::endl;
                    if (msgIt->second == "logout") {
                        shouldTerminate = true;
                        currentUsername.clear();
                        subscriptionCounter = 0;
                        receiptCounter = 0;
                        canonicalToSubId.clear();
                        subIdToCanonical.clear();
                        canonicalToDestination.clear();
                    }
                    receiptIdToCommand.erase(msgIt);
                }
            }
        }
    }
    else if (stompCommand == "MESSAGE") {
        std::string destination;
        for (const std::string& rawLine : lines) {
            if (rawLine.find("destination:/") == 0) {
                destination = trim(rawLine.substr(13));
                break;
            }
        }

        size_t bodyPos = frame.find("\n\n");
        if (bodyPos != std::string::npos) {
            std::string body = frame.substr(bodyPos + 2);
            if (!body.empty() && body.back() == '\0') {
                body.pop_back();
            }

            std::istringstream stream(body);
            std::string line;
            std::string user;
            std::string teamA;
            std::string teamB;
            std::string eventName;
            std::string description;
            int time = 0;
            std::map<std::string, std::string> genUpdates;
            std::map<std::string, std::string> teamAUpdates;
            std::map<std::string, std::string> teamBUpdates;
            std::string currentSection;

            while (std::getline(stream, line)) {
                if (line.empty()) {
                    continue;
                }
                std::string trimmedLine = trim(line);

                if (trimmedLine.find("user:") == 0) {
                    user = trim(trimmedLine.substr(5));
                } else if (trimmedLine.find("team a:") == 0 && trimmedLine.find("team a updates") == std::string::npos) {
                    teamA = trim(trimmedLine.substr(7));
                } else if (trimmedLine.find("team b:") == 0 && trimmedLine.find("team b updates") == std::string::npos) {
                    teamB = trim(trimmedLine.substr(7));
                } else if (trimmedLine.find("event name:") == 0) {
                    eventName = trim(trimmedLine.substr(11));
                } else if (trimmedLine.find("time:") == 0) {
                    try {
                        time = std::stoi(trim(trimmedLine.substr(5)));
                    } catch (...) {
                        time = 0;
                    }
                } else if (trimmedLine == "general game updates:") {
                    currentSection = "gen";
                } else if (trimmedLine == "team a updates:") {
                    currentSection = "a";
                } else if (trimmedLine == "team b updates:") {
                    currentSection = "b";
                } else if (trimmedLine == "description:") {
                    currentSection = "desc";
                    description.clear();
                } else if (currentSection == "desc") {
                    if (!description.empty()) {
                        description += '\n';
                    }
                    description += line;
                } else if (line.find("    ") == 0) {
                    size_t colon = line.find(':');
                    if (colon != std::string::npos) {
                        std::string key = trim(line.substr(4, colon - 4));
                        std::string val = trim(line.substr(colon + 1));
                        if (currentSection == "gen") genUpdates[key] = val;
                        else if (currentSection == "a") teamAUpdates[key] = val;
                        else if (currentSection == "b") teamBUpdates[key] = val;
                    }
                }
            }

            Event event(teamA, teamB, eventName, time, genUpdates, teamAUpdates, teamBUpdates, description);
            if (user.empty()) {
                user = "unknown";
            }
            event.set_event_owner(user);
            std::string canonicalGame = normalizeGameName(destination.empty() ? teamA + "_" + teamB : destination);
            if (!canonicalGame.empty()) {
                canonicalToDestination[canonicalGame] = destination.empty() ? canonicalToPretty(canonicalGame) : destination;
                storeEvent(canonicalGame, event);
            }
        }
    }
    else if (stompCommand == "ERROR") {
        std::cout << "Error from server: " << frame << std::endl;
        shouldTerminate = true;
        currentUsername.clear();
        subscriptionCounter = 0;
        receiptCounter = 0;
        canonicalToSubId.clear();
        subIdToCanonical.clear();
        canonicalToDestination.clear();
        receiptIdToCommand.clear();
    }
}
bool StompProtocol::isTerminated() const {
    std::lock_guard<std::mutex> lock(_mutex);
    return shouldTerminate;
}

void StompProtocol::markConnectionClosed() {
    std::lock_guard<std::mutex> lock(_mutex);
    shouldTerminate = true;
    currentUsername.clear();
    subscriptionCounter = 0;
    receiptCounter = 0;
    canonicalToSubId.clear();
    subIdToCanonical.clear();
    canonicalToDestination.clear();
    receiptIdToCommand.clear();
}

void StompProtocol::resetAfterSession() {
    std::lock_guard<std::mutex> lock(_mutex);
    shouldTerminate = false;
    receiptIdToCommand.clear();
}