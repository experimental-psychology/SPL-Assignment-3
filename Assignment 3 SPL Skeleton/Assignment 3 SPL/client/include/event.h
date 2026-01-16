#ifndef EVENT_H
#define EVENT_H

#include <string>
#include <vector>
#include <map>

struct Event {
    std::string event_name;
    int time;
    std::string description;
    std::map<std::string, std::string> general_updates;
    std::map<std::string, std::string> team_a_updates;
    std::map<std::string, std::string> team_b_updates;
};

struct names_and_events {
    std::string team_a;
    std::string team_b;
    std::vector<Event> events;
};

// Parse events from JSON file
names_and_events parseEventsFile(const std::string& json_path);

#endif