#include "event.h"
#include <fstream>
#include <sstream>
#include <iostream>
#include <algorithm>

// Forward declarations
static Event parseEvent(const std::string& event_json);
static std::map<std::string, std::string> parseUpdates(const std::string& json, const std::string& key);

names_and_events parseEventsFile(const std::string& json_path) {
    names_and_events result;
    std::ifstream file(json_path);
    
    if (!file.is_open()) {
        throw std::runtime_error("Could not open file: " + json_path);
    }
    
    std::string content((std::istreambuf_iterator<char>(file)),
                        std::istreambuf_iterator<char>());
    file.close();
    
    // Extract team_a
    size_t team_a_pos = content.find("\"team a\"");
    if (team_a_pos != std::string::npos) {
        size_t quote_pos = content.find("\"", team_a_pos + 9);
        size_t end_quote = content.find("\"", quote_pos + 1);
        result.team_a = content.substr(quote_pos + 1, end_quote - quote_pos - 1);
    }
    
    // Extract team_b
    size_t team_b_pos = content.find("\"team b\"");
    if (team_b_pos != std::string::npos) {
        size_t quote_pos = content.find("\"", team_b_pos + 9);
        size_t end_quote = content.find("\"", quote_pos + 1);
        result.team_b = content.substr(quote_pos + 1, end_quote - quote_pos - 1);
    }
    
    // Extract events array
    size_t events_pos = content.find("\"events\"");
    if (events_pos == std::string::npos) {
        throw std::runtime_error("No events found in JSON");
    }
    
    size_t array_start = content.find("[", events_pos);
    size_t array_end = content.rfind("]");
    
    std::string events_str = content.substr(array_start + 1, array_end - array_start - 1);
    
    // Parse each event
    size_t event_start = 0;
    int brace_count = 0;
    bool in_event = false;
    
    for (size_t i = 0; i < events_str.length(); ++i) {
        if (events_str[i] == '{') {
            if (brace_count == 0) {
                event_start = i;
                in_event = true;
            }
            brace_count++;
        } else if (events_str[i] == '}') {
            brace_count--;
            if (brace_count == 0 && in_event) {
                std::string event_json = events_str.substr(event_start, i - event_start + 1);
                Event e = parseEvent(event_json);
                result.events.push_back(e);
                in_event = false;
            }
        }
    }
    
    return result;
}

static Event parseEvent(const std::string& event_json) {
    Event e;
    
    size_t name_pos = event_json.find("\"event name\"");
    if (name_pos != std::string::npos) {
        size_t quote_pos = event_json.find("\"", name_pos + 13);
        size_t end_quote = event_json.find("\"", quote_pos + 1);
        e.event_name = event_json.substr(quote_pos + 1, end_quote - quote_pos - 1);
    }
    
    size_t time_pos = event_json.find("\"time\"");
    if (time_pos != std::string::npos) {
        size_t colon_pos = event_json.find(":", time_pos);
        size_t comma_pos = event_json.find(",", colon_pos);
        if (comma_pos == std::string::npos) comma_pos = event_json.find("}", colon_pos);
        std::string time_str = event_json.substr(colon_pos + 1, comma_pos - colon_pos - 1);
        time_str.erase(0, time_str.find_first_not_of(" \t\n\r"));
        time_str.erase(time_str.find_last_not_of(" \t\n\r") + 1);
        e.time = std::stoi(time_str);
    }
    
    size_t desc_pos = event_json.find("\"description\"");
    if (desc_pos != std::string::npos) {
        size_t quote_pos = event_json.find("\"", desc_pos + 14);
        size_t end_quote = event_json.rfind("\"");
        e.description = event_json.substr(quote_pos + 1, end_quote - quote_pos - 1);
    }
    
    e.general_updates = parseUpdates(event_json, "\"general game updates\"");
    e.team_a_updates = parseUpdates(event_json, "\"team a updates\"");
    e.team_b_updates = parseUpdates(event_json, "\"team b updates\"");
    
    return e;
}

static std::map<std::string, std::string> parseUpdates(const std::string& json, const std::string& key) {
    std::map<std::string, std::string> updates;
    size_t key_pos = json.find(key);
    if (key_pos == std::string::npos) return updates;
    
    size_t obj_start = json.find("{", key_pos);
    if (obj_start == std::string::npos) return updates;
    
    int brace_count = 0;
    size_t obj_end = obj_start;
    
    for (size_t i = obj_start; i < json.length(); ++i) {
        if (json[i] == '{') brace_count++;
        else if (json[i] == '}') {
            brace_count--;
            if (brace_count == 0) {
                obj_end = i;
                break;
            }
        }
    }
    
    std::string obj_str = json.substr(obj_start + 1, obj_end - obj_start - 1);
    size_t pos = 0;
    while (pos < obj_str.length()) {
        size_t quote_start = obj_str.find("\"", pos);
        if (quote_start == std::string::npos) break;
        size_t quote_end = obj_str.find("\"", quote_start + 1);
        if (quote_end == std::string::npos) break;
        
        std::string k = obj_str.substr(quote_start + 1, quote_end - quote_start - 1);
        size_t colon_pos = obj_str.find(":", quote_end);
        
        size_t value_start = obj_str.find("\"", colon_pos);
        if (value_start == std::string::npos) {
            size_t comma_or_brace = obj_str.find_first_of(",}", colon_pos);
            std::string v = obj_str.substr(colon_pos + 1, comma_or_brace - colon_pos - 1);
            v.erase(0, v.find_first_not_of(" \t\n\r"));
            v.erase(v.find_last_not_of(" \t\n\r") + 1);
            updates[k] = v;
            pos = comma_or_brace + 1;
        } else {
            size_t value_end = obj_str.find("\"", value_start + 1);
            std::string v = obj_str.substr(value_start + 1, value_end - value_start - 1);
            updates[k] = v;
            pos = value_end + 1;
        }
    }
    return updates;
}