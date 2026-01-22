# SPL Assignment 3 – STOMP Messaging System

A publish-subscribe messaging system for World Cup match updates using the STOMP protocol.

---

## How to Run

Open three terminals. All commands assume you start in:
```
/workspaces/SPL-Assignment-3/Assignment 3 SPL Skeleton/Assignment 3 SPL
```

---

### Terminal 1 – SQL Server

```bash
cd data
python3 sql_server.py
```

Runs on port 7778.

---

### Terminal 2 – STOMP Server

```bash
cd server
mvn compile
mvn exec:java -Dexec.mainClass="bgu.spl.net.impl.stomp.StompServer" -Dexec.args="7777 tpc"
```

Runs on port 7777 in TPC mode.

---

### Terminal 3 – Client

```bash
cd client
mkdir bin
make StompWCIClient
./bin/StompWCIClient
```

#### Example Commands

```
login 127.0.0.1:7777 alice pass
join Germany_Japan
report data/events1.json
summary Germany_Japan alice summary.txt
logout

login 127.0.0.1:7777 bob pass
join Germany_Japan
summary Germany_Japan bob summary_tpc.txt
logout
```

---

## Summary Behavior

A summary file is generated only after receiving:
- kickoff
- at least one goal
- halftime
- final whistle

Otherwise, the client prints:
```
Summary for <channel> is not ready yet.
```
