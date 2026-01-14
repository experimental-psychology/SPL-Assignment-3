#pragma once

#include "../include/ConnectionHandler.h"
#include <string>
#include <vector>
#include <map>
#include <mutex>

// החוזה של הפרוטוקול שלנו
class StompProtocol {
private:
    // --- משתני מצב ---
    bool isConnected; // האם אנחנו מחוברים?
    int subscriptionIdCounter; // מונה למזהי הרשמה
    int receiptIdCounter; // מונה למזהי אישור

    // --- ניהול מנויים ---
    // Game Name -> Subscription ID (כדי לדעת אם כבר נרשמנו)
    std::map<std::string, int> topicToSubId; 
    
    // Subscription ID -> Game Name (כדי לדעת ממה להתנתק)
    std::map<int, std::string> subIdToTopic;

    // --- ניהול אירועים לסיכום (Summary) ---
    // Game Name -> (Username -> List of Events Strings)
    // אנחנו שומרים את האירועים כ-Strings כדי להדפיס אותם בסוף
    std::map<std::string, std::map<std::string, std::vector<std::string>>> gameEvents;
    
public:
    StompProtocol(); // בנאי
    
    // פונקציה לעיבוד קלט מהמקלדת (login, join...)
    // מחזירה true אם הפקודה דורשת שליחה לשרת
    // מעדכנת את ה-frameOut עם המחרוזת שיש לשלוח
    bool processKeyboardCommand(const std::string& commandLine, std::string& frameOut);

    // פונקציה לעיבוד הודעות שהתקבלו מהשרת
    // מחזירה true אם הכל תקין, false אם צריך להתנתק
    bool processServerFrame(const std::string& frame);

    // האם הלקוח מחובר לוגית?
    bool shouldTerminate();
    void setConnected(bool status);
};