#include "../include/event.h"
#include "../include/json.hpp"
#include <iostream>
#include <fstream>
#include <string>
#include <map>
#include <vector>
#include <sstream>
using json = nlohmann::json;

Event::Event(std::string team_a_name, std::string team_b_name, std::string name, int time,
             std::map<std::string, std::string> game_updates, std::map<std::string, std::string> team_a_updates,
             std::map<std::string, std::string> team_b_updates, std::string description)
    : team_a_name(team_a_name), team_b_name(team_b_name), name(name),
      time(time), game_updates(game_updates), team_a_updates(team_a_updates),
      team_b_updates(team_b_updates), description(description)
{
}
Event::Event(const std::string & frame_body) : team_a_name(""), team_b_name(""), name(""), time(0), game_updates(), team_a_updates(), team_b_updates(), description(""), event_owner("")
{
    std::istringstream stream(frame_body);
    std::string line;
    std::string currentSection = "";

    while (std::getline(stream, line)) {
        if (line.empty()) continue;
        if (!line.empty() && line[line.size() - 1] == '\r') line.erase(line.size() - 1);

        if (line.find("user:") == 0) event_owner = line.substr(5);
        else if (line.find("team a:") == 0 && line.find("updates") == std::string::npos) team_a_name = line.substr(7);
        else if (line.find("team b:") == 0 && line.find("updates") == std::string::npos) team_b_name = line.substr(7);
        else if (line.find("event name:") == 0) name = line.substr(11);
        else if (line.find("time:") == 0) time = std::stoi(line.substr(5));
        else if (line.find("general game updates:") == 0) currentSection = "gen";
        else if (line.find("team a updates:") == 0) currentSection = "a";
        else if (line.find("team b updates:") == 0) currentSection = "b";
        else if (line.find("description:") == 0) currentSection = "desc";
        else if (line.find("    ") == 0) { 
            size_t colon = line.find(':');
            if (colon != std::string::npos) {
                std::string key = line.substr(4, colon - 4);
                std::string val = line.substr(colon + 1);
                if (currentSection == "gen") game_updates[key] = val;
                else if (currentSection == "a") team_a_updates[key] = val;
                else if (currentSection == "b") team_b_updates[key] = val;
            }
        }
        else if (currentSection == "desc") {
            description += line + "\n";
        }
    }
}
Event::~Event()
{
}

const std::string &Event::get_team_a_name() const
{
    return this->team_a_name;
}
const std::string &Event::get_event_owner() const { return this->event_owner; }
void Event::set_event_owner(std::string user) { this->event_owner = user; }
const std::string &Event::get_team_b_name() const
{
    return this->team_b_name;
}
const std::string &Event::get_name() const
{
    return this->name;
}
int Event::get_time() const
{
    return this->time;
}
const std::map<std::string, std::string> &Event::get_game_updates() const
{
    return this->game_updates;
}
const std::map<std::string, std::string> &Event::get_team_a_updates() const
{
    return this->team_a_updates;
}
const std::map<std::string, std::string> &Event::get_team_b_updates() const
{
    return this->team_b_updates;
}
const std::string &Event::get_description() const
{
    return this->description;
}
names_and_events parseEventsFile(std::string json_path)
{
    std::ifstream f(json_path);
    json data = json::parse(f);

    std::string team_a_name = data["team a"];
    std::string team_b_name = data["team b"];
    std::vector<Event> events;
    for (auto &event : data["events"])
    {
        std::string name = "";
        if (event.contains("event name") && !event["event name"].is_null()) {
            name = event["event name"];
        }
        
        int time = 0;
        if (event.contains("time") && !event["time"].is_null()) {
            time = event["time"];
        }
        
        std::string description = "";
        if (event.contains("description") && !event["description"].is_null()) {
            description = event["description"];
        }
        
        std::string event_owner = "";
        if (event.contains("event owner") && !event["event owner"].is_null()) {
            event_owner = event["event owner"];
        }
        
        std::map<std::string, std::string> game_updates;
        std::map<std::string, std::string> team_a_updates;
        std::map<std::string, std::string> team_b_updates;
        for (auto &update : event["general game updates"].items())
        {
            if (update.value().is_string())
                game_updates[update.key()] = update.value();
            else
                game_updates[update.key()] = update.value().dump();
        }

        for (auto &update : event["team a updates"].items())
        {
            if (update.value().is_string())
                team_a_updates[update.key()] = update.value();
            else
                team_a_updates[update.key()] = update.value().dump();
        }

        for (auto &update : event["team b updates"].items())
        {
            if (update.value().is_string())
                team_b_updates[update.key()] = update.value();
            else
                team_b_updates[update.key()] = update.value().dump();
        }
        
        events.push_back(Event(team_a_name, team_b_name, name, time, game_updates, team_a_updates, team_b_updates, description));
    }
    names_and_events events_and_names{team_a_name, team_b_name, events};

    return events_and_names;
}