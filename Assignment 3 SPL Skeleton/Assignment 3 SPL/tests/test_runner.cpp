#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "../client/include/StompProtocol.h"
#include "../client/include/event.h"

namespace {

std::string readFile(const std::string& path) {
    std::ifstream in(path.c_str(), std::ios::binary);
    if (!in.is_open()) {
        throw std::runtime_error("Failed to open file: " + path);
    }
    std::ostringstream buffer;
    buffer << in.rdbuf();
    return buffer.str();
}

std::string referenceSummary() {
    return std::string(
        "Germany vs Japan\n"
        "Game stats:\n"
        "General stats:\n"
        "active: false\n"
        "before halftime: false\n"
        "Germany stats:\n"
        "goals: 1\n"
        "possession: 51%\n"
        "Japan stats:\n"
        "goals: 2\n"
        "possession: 49%\n"
        "Game event reports:\n"
        "0 - kickoff:\n\n"
        "The game has started! What an exciting evening!\n\n"
        "1980 - goal!!!!:\n\n"
        "GOOOAAALLL!!! Germany lead!!! Gundogan finally has success in the box as he steps up to take the penalty, "
        "sends Gonda the wrong way, and slots the ball into the left-hand corner to put Germany 1-0 up! A needless penalty to "
        "concede from Japan's point of view, and after a bright start, the Samurai Blues trail!\n\n"
        "2940 - Another goal!!!!:\n\n"
        "BALL IN THE NET!!! Germany think they've doubled their lead following a brilliant passage of play, but there looks "
        "to an issue of offside as Havertz wheels away! Muller's cross from the right is headed away to Kimmich, who hits a low shot "
        "towards the bottom corner Gonda sees it late and parries it away, but Gnabry drills the ball back across the face and Havertz "
        "taps it in! The goal is given initially, but no doubt we'll be going upstairs for a second look---\n\n"
        "3000 - No goal:\n\n"
        "No goal! After a VAR review, a goal for Germany is ruled out.\n\n"
        "3060 - halftime:\n\n"
        "The first half ends, and what a half it's been! Germany almost suffered an early scare as Maeda tapped in after just "
        "eight minutes, but his offside position gave the 2014 champions a huge let-off This spooked them into gear, and the "
        "remainder of the half was dominated by the Germans, who peppered the Japan goal winning a 33rd-minute Gundogan slotted "
        "it home to put Die Nationalelf into a deserved lead. They almost doubled it half-time, as Havertz finished off a great "
        "attacking move, but he was also caught in an offside position, meaning the lead is still just one goal at the break.\n\n"
        "4500 - goalgoalgoalgoalgoal!!!:\n\n"
        "GOOOOAAAALLLL!!!!! Japan have parity and boy do they deserve it!!! Mitoma drives fowrard before feeding Minamino, whose "
        "ball across the face is parried away by Neuer. Doan reacts quickest to smash the ball into the back of the net, and the "
        "three Japan substitutes combine to put them back on level terms at 1-1!\n\n"
        "4980 - goalgoalgoalgoalgoal!!!:\n\n"
        "GOOOOOOAAAAALLLL!!!! Can you believe Itakura's long ball from a free-kick in his own half is met by Asano, who beats Schlotterbeck "
        "with a beauty of a first touch. He darts into the box and smashes a shot from the tightest of angles, which beats Neuer and "
        "flies into the roof of the net!!! What a turnaround, what a story, and what a finish, to put Japan 2-1 up!!!\n\n"
        "5400 - final whistle:\n\n"
        "Well, what a way to kick off Group E! Germany sit at the bottom of the group following that defeat, while Japan are at the top "
        "with one win from one! Spain and Costa Rica are yet to play their corresponding fixture, but if the rest of the games are anything "
        "like this one, we're in for one hell of a treat, and a very competitive set of fixtures.\n\n"
    );
}

void writeMessageFrameBody(std::ostringstream& body, const Event& event, const std::string& user) {
    body << "user:" << user << "\n";
    body << "team a:" << event.get_team_a_name() << "\n";
    body << "team b:" << event.get_team_b_name() << "\n";
    body << "event name:" << event.get_name() << "\n";
    body << "time:" << event.get_time() << "\n";

    body << "general game updates:\n";
    for (const auto& entry : event.get_game_updates()) {
        body << "    " << entry.first << ":" << entry.second << "\n";
    }

    body << "team a updates:\n";
    for (const auto& entry : event.get_team_a_updates()) {
        body << "    " << entry.first << ":" << entry.second << "\n";
    }

    body << "team b updates:\n";
    for (const auto& entry : event.get_team_b_updates()) {
        body << "    " << entry.first << ":" << entry.second << "\n";
    }

    body << "description:\n";
    body << event.get_description() << "\n";
}

std::string buildMessageFrame(const std::string& destination, const Event& event, const std::string& user) {
    std::ostringstream body;
    writeMessageFrameBody(body, event, user);

    std::ostringstream frame;
    frame << "MESSAGE\n";
    frame << "subscription:0\n";
    frame << "message-id:1\n";
    frame << "destination:/" << destination << "\n\n";
    frame << body.str();
    return frame.str();
}

void removeIfExists(const std::string& path) {
    std::remove(path.c_str());
}

bool fileExists(const std::string& path) {
    std::ifstream in(path.c_str());
    return in.good();
}

std::size_t fileSize(const std::string& path) {
    std::ifstream in(path.c_str(), std::ios::binary | std::ios::ate);
    if (!in.is_open()) {
        return 0;
    }
    return static_cast<std::size_t>(in.tellg());
}

void ensureSummaryMatchesReference(const std::string& summaryPath) {
    const std::string produced = readFile(summaryPath);
    const std::string reference = referenceSummary();
    if (produced != reference) {
        throw std::runtime_error("Summary does not match reference for file: " + summaryPath);
    }
}

void runFullSummaryTest() {
    StompProtocol protocol;
    protocol.processInput("login 127.0.0.1:7777 alice pass");
    protocol.processInput("join Germany_Japan");
    protocol.processInput("report ../client/data/events1.json");

    const std::string outputPath = "tmp_full_summary.txt";
    removeIfExists(outputPath);

    protocol.processInput("summary Germany_Japan alice " + outputPath);

    if (!fileExists(outputPath)) {
        throw std::runtime_error("Expected summary file was not created for full game test");
    }
    ensureSummaryMatchesReference(outputPath);
    removeIfExists(outputPath);
}

void runIncompleteSummaryTest() {
    StompProtocol protocol;
    protocol.processInput("login 127.0.0.1:7777 alice pass");
    protocol.processInput("join Germany_Japan");
    protocol.processInput("report ../client/data/events1_partial.json");

    const std::string outputPath = "tmp_incomplete_summary.txt";
    removeIfExists(outputPath);

    protocol.processInput("summary Germany_Japan alice " + outputPath);

    if (!fileExists(outputPath)) {
        throw std::runtime_error("Summary command should create (empty) file even when timeline is incomplete");
    }
    const std::size_t size = fileSize(outputPath);
    if (size != 0) {
        throw std::runtime_error("Incomplete timeline should not produce summary content");
    }
    removeIfExists(outputPath);
}

void runFallbackSummaryTest() {
    StompProtocol protocol;
    protocol.processInput("login 127.0.0.1:7777 alice pass");
    protocol.processInput("join Germany_Japan");
    protocol.processInput("report ../client/data/events1_partial.json");

    names_and_events fullTimeline = parseEventsFile("../client/data/events1.json");
    for (const Event& original : fullTimeline.events) {
        Event eventCopy = original;
        eventCopy.set_event_owner("bob");
        std::string frame = buildMessageFrame("Germany_Japan", eventCopy, "bob");
        protocol.processResponse(frame);
    }

    const std::string outputPath = "tmp_fallback_summary.txt";
    removeIfExists(outputPath);

    protocol.processInput("summary Germany_Japan alice " + outputPath);

    if (!fileExists(outputPath)) {
        throw std::runtime_error("Fallback summary file was not created");
    }
    ensureSummaryMatchesReference(outputPath);
    removeIfExists(outputPath);
}

} // namespace

int main() {
    try {
        runFullSummaryTest();
        runIncompleteSummaryTest();
        runFallbackSummaryTest();
    } catch (const std::exception& ex) {
        std::cerr << "Test failure: " << ex.what() << std::endl;
        return 1;
    }

    std::cout << "All tests passed" << std::endl;
    return 0;
}
