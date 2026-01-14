#include "../include/StompProtocol.h"
#include "../include/event.h"
#include <iostream>
#include <sstream>

StompProtocol::StompProtocol() : 
    isConnected(false), 
    subscriptionIdCounter(0), 
    receiptIdCounter(0) {}

bool StompProtocol::processKeyboardCommand(const std::string& commandLine, std::string& frameOut) {
    // כאן נכתוב את הלוגיקה של המרת פקודות ל-STOMP Frames
    // כרגע נחזיר סתם את מה שהמשתמש כתב כדי לבדוק תקשורת
    if (commandLine.find("login") == 0) {
        // דוגמה ידנית להתחברות (זמני)
        frameOut = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:meni\npasscode:films\n\n\0";
        return true;
    }
    return false;
}

bool StompProtocol::processServerFrame(const std::string& frame) {
    // כאן נדפיס את מה שמגיע מהשרת
    std::cout << "Server says: " << frame << std::endl;
    
    if (frame.find("CONNECTED") != std::string::npos) {
        isConnected = true;
        std::cout << "Login successful!" << std::endl;
    }
    return true;
}

bool StompProtocol::shouldTerminate() {
    return false; // כרגע לא מתנתקים אף פעם
}

void StompProtocol::setConnected(bool status) {
    isConnected = status;
}