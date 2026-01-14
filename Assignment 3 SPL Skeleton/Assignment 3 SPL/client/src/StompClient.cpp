#include <iostream>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"
#include <thread>

int main(int argc, char *argv[]) {
    // 1. בדיקת ארגומנטים (כמו ב-EchoClient)
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " host port" << std::endl;
        return -1;
    }
    std::string host = argv[1];
    short port = atoi(argv[2]);

    // 2. יצירת החיבור
    ConnectionHandler connectionHandler(host, port);
    if (!connectionHandler.connect()) {
        std::cerr << "Cannot connect to " << host << ":" << port << std::endl;
        return 1;
    }

    // 3. יצירת הפרוטוקול
    StompProtocol protocol;

    // 4. Thread רקע: האזנה לשרת (Socket Listener)
    std::thread socketThread([&connectionHandler, &protocol]() {
        while (true) {
            std::string answer;
            // קריאת שורה/פריים מהשרת
            if (!connectionHandler.getFrameAscii(answer, '\0')) {
                std::cout << "Disconnected from server" << std::endl;
                break;
            }
            // עיבוד התשובה בפרוטוקול
            if (!protocol.processServerFrame(answer)) {
                break; 
            }
        }
    });

    // 5. Thread ראשי: קריאה מהמקלדת (Keyboard Listener)
    while (true) {
        const short bufsize = 1024;
        char buf[bufsize];
        std::cin.getline(buf, bufsize);
        std::string line(buf);
        std::string frameOut;

        // עיבוד הפקודה: אם הפרוטוקול אומר שיש משהו לשלוח - שולחים
        if (protocol.processKeyboardCommand(line, frameOut)) {
            if (!connectionHandler.sendFrameAscii(frameOut, '\0')) {
                std::cout << "Disconnected. Exiting...\n" << std::endl;
                break;
            }
        }
        
        // כאן נוסיף בהמשך בדיקה אם המשתמש עשה logout
    }

    // המתנה לסיום הת'רד השני לפני סגירה
    socketThread.join();
    return 0;
}