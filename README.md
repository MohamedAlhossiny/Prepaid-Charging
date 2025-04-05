# Prepaid Charging for Voice Call

This project implements a voice call system with real-time prepaid charging. It consists of two Java applications:

1. **Mobile Phone Application**: Captures voice from the microphone and encrypt it with AES algorithm, then sends it via UDP to the MSC.
2. **Mobile Switching Center (MSC) Application**: Receives voice data via UDP, plays it on the PC speaker, and handles call charging.

## Requirements

- Java Development Kit (JDK) 8 or higher
- Access to microphone and speaker (optional - test mode available)

## How to Compile

```bash
javac Mobile.java
javac MSC.java
```

## How to Run

### Step 1: Start the MSC Application

```bash
java MSC
```

You should see output like:
```
Created directory: C:\path\to\project\voice
Created directory: C:\path\to\project\CDR
Started TCP signaling server on port 5011
Started UDP voice socket on port 5011
MSC ready - waiting for voice call signaling start message via TCP
```

### Step 2: List Available Microphones (Optional)

To see all available microphones on your system:

```bash
java Mobile list
```

This will display all microphones with their index numbers, for example:
```
=== Available Audio Capture Devices ===
0: Built-in Microphone - Built-in Audio Device
1: Webcam Microphone - USB Audio Device
2: Bluetooth Headset - Bluetooth Audio Device
=======================================
```

### Step 3: Start the Mobile Application

Start without specifying a microphone (uses default microphone):

```bash
java Mobile <MSISDN>
```

Or start with a specific microphone by index:

```bash
java Mobile <MSISDN> <microphone-index>
```

For example:
```bash
java Mobile 01223456789
```

Or to use a specific microphone (e.g., #1 from the list):
```bash
java Mobile 01223456789 1
```

You should see output like:
```
Starting voice call as MSISDN 01223456789
Sent start call signaling to MSC at localhost:5011
Started signaling listener to receive messages from MSC
Created UDP socket from port 49152 to localhost:5011
Running in MICROPHONE MODE - capturing actual audio
=== Available Audio Capture Devices ===
0: Built-in Microphone - Built-in Audio Device
1: Webcam Microphone - USB Audio Device
Using default microphone
=======================================
Trying to open default microphone
Successfully opened default microphone
Capturing voice from microphone and sending via UDP...
```

## Test Mode

The Mobile application includes a test mode that generates audio tones instead of capturing from the microphone. This can be changed by modifying the source code:

```java
private static final boolean TEST_MODE = false; // Set to true for test mode
```

Test mode is useful for:
- Testing when no microphone is available
- Ensuring consistent audio quality for debugging
- Validating the audio pipeline with known frequencies

## Microphone Selection

The Mobile app now provides several ways to work with microphones:

1. **Automatic microphone detection**: Lists all available audio capture devices
2. **Default microphone**: Used when no specific microphone is selected
3. **Specific microphone selection**: Select by index number
4. **Fallback mechanism**: If selected microphone fails, tries default, then test mode

## File Storage

The system organizes call data into separate directories:

1. **Call Recordings**: All voice recordings are saved in the `/voice` directory
   - Format: `voice/voice_call_msisdn_<number>_date_<date>_Time_<time>.wav`
   - Example: `voice/voice_call_msisdn_01223456789_date_2025_03_01_Time_10_20_30.wav`
   - Audio Format: 44.1kHz, 16-bit, mono WAV files

2. **Call Detail Records (CDRs)**: All billing records are saved in the `/CDR` directory
   - File: `CDR/calls.cdr` (append-only text file)
   - Each call adds a new line to this file with complete billing information
   - Failed calls (e.g., due to insufficient balance) are also recorded with reason

Both directories are created automatically when the MSC application starts.

## Balance Management

The system implements strict balance management for prepaid calling:

### Call Initiation
- **Balance Check**: When a call is initiated, the MSC checks if the user has sufficient balance for at least one minute
- **Call Rejection**: If balance < 5 L.E., the call is immediately rejected, an error CDR is generated, and the mobile is notified
- **Balance Display**: The system shows the user's current balance at call start

### During Calls
- **Per-Minute Charging**: Every minute, the system charges the user's account
- **Real-time Balance Updates**: Balance is updated in real-time as charges occur
- **Automatic Termination**: If balance reaches zero or goes below the per-minute charge, the call is automatically terminated and the mobile is notified
- **Exact Zero Balance Handling**: Calls are terminated immediately when the balance reaches exactly zero
- **Partial Charging**: If balance is insufficient for a full minute, only the available balance is charged

### Call Completion
- **Final Charging**: At call end, the system calculates the total cost based on duration
- **Balance Protection**: The system prevents negative balances by limiting charges to the available balance
- **Detailed Reporting**: CDRs include the reason for call termination (normal or insufficient balance)

## Call Termination Handling

The system implements proper call termination handling between the MSC and Mobile:

1. **Balance-Based Termination**: 
   - When a call is rejected or terminated due to insufficient balance, the MSC sends a termination message to the Mobile
   - The Mobile application receives these messages and gracefully exits
   - Users see clear notification messages explaining why their call was terminated

2. **Termination Messages**:
   - Format: `TERMINATE_CALL:<reason>`
   - Common reasons: "Insufficient Balance", "User Not Found", "Insufficient Balance for Call"
   - Mobile displays these reasons to help users understand why their call ended

3. **Mobile Application Behavior**:
   - On receiving a termination message, the Mobile application displays a notification
   - The application automatically closes after showing the termination reason
   - This prevents the Mobile from continuing to send voice data after the MSC has terminated the call

## Call Charging Details

The system implements a ceiling-based charging model:

- Charging rate: 5 L.E. per minute
- **Ceiling-based minute calculation**: Any partial minute is rounded up to a full minute
  - Example 1: Call duration of 45 seconds = 1 minute billable time = 5 L.E.
  - Example 2: Call duration of 1 minute and 5 seconds = 2 minutes billable time = 10 L.E.
- Real-time charging occurs every minute
- When a new minute starts, it is immediately charged (even if the call ends partway through that minute)
- When a call ends, the system generates a Call Detail Record (CDR) in the `CDR/calls.cdr` file

## Predefined Users

The MSC application comes with predefined users and balances:
- MSISDN: 01223456789, Balance: 100.0 L.E.
- MSISDN: 01234567890, Balance: 50.0 L.E.
- MSISDN: 01112223333, Balance: 25.0 L.E.
- MSISDN: 01020053936, Balance: 5.0 L.E. (for testing exact one-minute call)

## CDR Format

CDR entries are written to `CDR/calls.cdr` in the following format:
```
MSISDN, StartTime, EndTime, ActualDuration, BillableMinutes, CallResult, CallCost, BalanceAfterCall
```

Example for successful call:
```
01223456789, 2025-03-01T15:24:47.398253, 2025-03-01T15:26:47.398253, 2:05, 3, Normal call Clearing, 15.00, 85.00
```

Example for rejected call due to insufficient balance:
```
01020053936, 2025-03-01T15:24:47.398253, 2025-03-01T15:24:47.398253, 0:00, 0, Insufficient Balance, 0.00, 0.00
```

Example for terminated call due to depleted balance:
```
01020053936, 2025-03-01T15:24:47.398253, 2025-03-01T15:25:47.398253, 1:00, 1, Insufficient Balance, 5.00, 0.00
```

- **MSISDN**: The phone number
- **StartTime**: When the call started
- **EndTime**: When the call ended
- **ActualDuration**: The actual duration in minutes:seconds format (e.g., 2:05 means 2 minutes and 5 seconds)
- **BillableMinutes**: The number of minutes charged (rounded up)
- **CallResult**: Status of call termination (Normal call Clearing or Insufficient Balance)
- **CallCost**: Total charge in L.E.
- **BalanceAfterCall**: Remaining balance after the call

## Technical Details

- The Mobile application uses a TCP connection to signal call start/end to the MSC.
- Voice data is transmitted via UDP on port 5011.
- Signaling occurs on TCP port 5011.
- The Mobile application automatically sends an end call signal when the application is shut down.
- The MSC sends termination messages to the Mobile when a call is rejected or terminated due to insufficient balance.
- Audio is sampled at 44100Hz, 16-bit, mono.
- AES key is exchanged by RSA public/private keys.
- Both applications include extensive debug output to help diagnose issues.

## Troubleshooting

If you experience issues:

1. **No audio playing**: Check if the MSC is properly receiving UDP packets. The console should show "Playing audio from MSISDN" messages.
2. **Socket errors**: Ensure both ports (TCP and UDP) are available and not blocked by a firewall.
3. **Microphone not working**: 
   - Try listing available microphones with `java Mobile list`
   - Select a specific microphone by index: `java Mobile 01223456789 1`
   - Check if your microphone is enabled in your OS settings
   - Make sure no other application is using the microphone
   - The Mobile app will automatically switch to test mode if no microphone is available
4. **File access issues**:
   - Ensure the application has write permissions to create the `/voice` and `/CDR` directories
   - Check console output for any file creation or access errors
   - Check if the directories were created successfully at startup
5. **Balance issues**:
   - If your call is rejected immediately, check the CDR file for "Insufficient Balance" entries
   - For testing exact one-minute calls, use MSISDN 01020053936 which has 5.0 L.E. balance (enough for exactly one minute)
   - For testing in-call termination due to balance depletion, use MSISDN 01112223333 which has only 25 L.E. (enough for 5 minutes)
6. **Call termination issues**:
   - If the Mobile application does not close after MSC terminates the call, restart both applications
   - Check console output on both MSC and Mobile for termination messages
   - Ensure your network allows bidirectional TCP communication between Mobile and MSC