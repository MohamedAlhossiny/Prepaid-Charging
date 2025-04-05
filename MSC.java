import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

public class MSC {
    private static final int UDP_PORT = 5011;
    private static final int SIGNALING_PORT = 5011;
    private static final int SAMPLE_RATE = 44100;
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1;
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    private static final String VOICE_DIR = "voice";
    private static final String CDR_DIR = "CDR";
    private static final String CDR_FILE_NAME = "calls.cdr";
    private static final double CHARGE_RATE = 5.0; // 5 L.E per minute
    private static final int BUFFER_SIZE = 1024;
    
    private ServerSocket signalingServer;
    private DatagramSocket voiceSocket;
    private Map<String, UserCall> activeCalls;
    private Map<String, Double> userBalances;
    private Map<String, Socket> clientSockets;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = true;
    
    // Class to track call details
    private static class UserCall {
        String msisdn;
        LocalDateTime startTime;
        LocalDateTime endTime;
        InetAddress address;
        int port;
        boolean active;
        double initialBalance;
        double currentBalance;
        AudioInputStream audioStream;
        ByteArrayOutputStream audioData;
        
        public UserCall(String msisdn, InetAddress address, int port, double balance) {
            this.msisdn = msisdn;
            this.startTime = LocalDateTime.now();
            this.address = address;
            this.port = port;
            this.active = true;
            this.initialBalance = balance;
            this.currentBalance = balance;
            
            // Initialize audio recording
            this.audioData = new ByteArrayOutputStream();
        }
    }
    
    public MSC() {
        activeCalls = new ConcurrentHashMap<>();
        userBalances = new HashMap<>();
        clientSockets = new ConcurrentHashMap<>();
        scheduler = Executors.newScheduledThreadPool(2);
        
        // Initialize some user balances (in a real system, would be loaded from a database)
        userBalances.put("01223456789", 100.0);
        userBalances.put("01234567890", 50.0);
        userBalances.put("01112223333", 25.0);
        userBalances.put("01020053936", 5.0);
        
        
        // Create required directories if they don't exist
        createDirectoryIfNotExists(VOICE_DIR);
        createDirectoryIfNotExists(CDR_DIR);
    }
    
    private void createDirectoryIfNotExists(String dirPath) {
        try {
            Path path = Paths.get(dirPath);
            if (!Files.exists(path)) {
                Files.createDirectory(path);
                System.out.println("Created directory: " + path.toAbsolutePath());
            } else {
                System.out.println("Directory already exists at: " + path.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Error creating directory " + dirPath + ": " + e.getMessage());
        }
    }
    
    public void start() {
        try {
            // Setup TCP server for signaling
            signalingServer = new ServerSocket(SIGNALING_PORT);
            System.out.println("Started TCP signaling server on port " + SIGNALING_PORT);
            
            // Setup UDP socket for voice data
            voiceSocket = new DatagramSocket(UDP_PORT);
            System.out.println("Started UDP voice socket on port " + UDP_PORT);
            
            System.out.println("MSC ready - waiting for voice call signaling start message via TCP");
            
            // Start thread to handle signaling messages
            new Thread(this::handleSignaling).start();
            
            // Start thread to handle voice data
            new Thread(this::handleVoiceData).start();
            
            // Start charging scheduler
            scheduler.scheduleAtFixedRate(this::chargeActiveCalls, 1, 1, TimeUnit.MINUTES);
            
            // Add shutdown hook to clean up resources
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                cleanup();
            }));
            
            // Keep main thread alive
            while (running) {
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            System.err.println("Error starting MSC: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Error in main thread: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleSignaling() {
        while (running) {
            try {
                Socket clientSocket = signalingServer.accept();
                new Thread(() -> processSignalingClient(clientSocket)).start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting signaling connection: " + e.getMessage());
                }
            }
        }
    }
    
    private void processSignalingClient(Socket clientSocket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String message;
            
            while ((message = in.readLine()) != null) {
                if (message.startsWith("START_CALL:")) {
                    String msisdn = message.substring("START_CALL:".length());
                    storeClientSocket(msisdn, clientSocket);
                    handleStartCall(msisdn, clientSocket.getInetAddress());
                } else if (message.startsWith("END_CALL:")) {
                    String msisdn = message.substring("END_CALL:".length());
                    handleEndCall(msisdn);
                }
            }
        } catch (IOException e) {
            System.err.println("Error processing signaling client: " + e.getMessage());
        }
    }
    
    private void storeClientSocket(String msisdn, Socket socket) {
        clientSockets.put(msisdn, socket);
        System.out.println("Stored client socket for MSISDN: " + msisdn);
    }
    
    private void handleStartCall(String msisdn, InetAddress callerAddress) {
        if (!userBalances.containsKey(msisdn)) {
            System.out.println("User not found: " + msisdn + " - rejecting call");
            // Sending rejection message to the mobile
            sendTerminationMessage(msisdn, "User Not Found");
            return;
        }
        
        double balance = userBalances.get(msisdn);
        System.out.println("User " + msisdn + " current balance: " + balance + " L.E.");
        
        if (balance < CHARGE_RATE) {
            System.out.println("Insufficient balance for user: " + msisdn + 
                             " (has " + balance + " L.E., needs at least " + CHARGE_RATE + " L.E.)");
            System.out.println("Rejecting call due to insufficient funds");
            
            try {
                // Generate error CDR for insufficient balance
                generateErrorCDR(msisdn, balance, "Insufficient Balance");
                
                // Send rejection message to the mobile
                sendTerminationMessage(msisdn, "Insufficient Balance for Call");
            } catch (Exception e) {
                System.err.println("Error generating error CDR: " + e.getMessage());
            }
            
            return;
        }
        
        System.out.println("Accept Voice call start signaling message from MSISDN " + msisdn);
        System.out.println("Caller address: " + callerAddress.getHostAddress());
        
        UserCall call = new UserCall(msisdn, callerAddress, UDP_PORT, balance);
        activeCalls.put(msisdn, call);
        
        System.out.println("Capturing UDP traffic and play via speaker .....");
    }
    
    private void handleEndCall(String msisdn) {
        UserCall call = activeCalls.get(msisdn);
        if (call != null) {
            call.active = false;
            call.endTime = LocalDateTime.now();
            activeCalls.remove(msisdn);
            
            // Calculate call duration and cost
            long durationSeconds = ChronoUnit.SECONDS.between(call.startTime, call.endTime);
            
            // Calculate billable minutes - ceiling-based (round up to next minute)
            // If call duration is 1 second, charge for 1 minute
            // If call duration is 61 seconds, charge for 2 minutes
            long billableMinutes = (durationSeconds + 59) / 60; // Ceiling division
            if (billableMinutes < 1) billableMinutes = 1; // Minimum 1 minute
            
            double callCost = billableMinutes * CHARGE_RATE;
            
            // Get current balance
            double currentBalance = userBalances.get(msisdn);
            
            // Check if user has enough balance for the entire call
            if (currentBalance < callCost) {
                // User doesn't have enough balance for the full call cost
                // Charge only what they have left
                callCost = currentBalance;
                System.out.println("Warning: User " + msisdn + " has insufficient balance to cover full call cost.");
                System.out.println("Charging only the available balance: " + callCost + " L.E.");
            }
            
            // Update user balance
            double finalBalance = Math.max(0, currentBalance - callCost);
            userBalances.put(msisdn, finalBalance);
            
            // Calculate actual minutes for display/logging
            long actualMinutes = ChronoUnit.MINUTES.between(call.startTime, call.endTime);
            long remainingSeconds = durationSeconds - (actualMinutes * 60);
            
            System.out.println("Call End after receiving end call signaling message");
            System.out.println("Call duration: " + actualMinutes + " minutes, " + remainingSeconds + " seconds");
            System.out.println("Billable minutes: " + billableMinutes);
            System.out.println("Call cost: " + callCost + " L.E.");
            System.out.println("Previous balance: " + currentBalance + " L.E.");
            System.out.println("New balance: " + finalBalance + " L.E.");
            
            // Save call audio to WAV file
            saveCallAudio(call);
            
            // Generate CDR
            generateCDR(call, billableMinutes, durationSeconds, callCost, finalBalance, "Normal call Clearing");
        }
    }
    
    private void saveCallAudio(UserCall call) {
        if (call.audioData == null || call.audioData.size() == 0) {
            System.out.println("No audio data available for recording");
            return;
        }
        
        try {
            // Format the date and time parts for the filename
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd");
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH_mm_ss");
            String date = call.startTime.format(dateFormatter);
            String time = call.startTime.format(timeFormatter);
            
            // Create filename as per format: /voice/voice_call_msisdn_<number>_date_<date>_Time_<time>.wav
            String filename = String.format("%s/voice_call_msisdn_%s_date_%s_Time_%s.wav", 
                                VOICE_DIR, call.msisdn, date, time);
            
            // Create AudioInputStream from the collected bytes
            byte[] audioBytes = call.audioData.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(audioBytes);
            
            // Create AudioFormat with the same parameters used for capture
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, 
                                               CHANNELS, SIGNED, BIG_ENDIAN);
            
            AudioInputStream audioInputStream = new AudioInputStream(bais, format, audioBytes.length / format.getFrameSize());
            
            // Write to WAV file
            File outputFile = new File(filename);
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, outputFile);
            
            System.out.println("Call recording saved to: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Error saving call audio: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void generateErrorCDR(String msisdn, double balance, String reason) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            LocalDateTime now = LocalDateTime.now();
            
            String cdrLine = String.format("%s, %s, %s, %s, %s, %s, %.2f, %.2f\n",
                msisdn,
                now.format(formatter),
                now.format(formatter),
                "0:00",    // Duration: 0 minutes, 0 seconds
                "0",       // 0 billable minutes
                reason,    // Error reason
                0.0,       // No cost
                balance);  // Balance remains the same
            
            // Get the full path to the CDR file
            Path cdrFilePath = Paths.get(CDR_DIR, CDR_FILE_NAME);
            
            // Append CDR to file
            Files.write(cdrFilePath, cdrLine.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            System.out.println("Generating error CDR line: " + cdrLine.trim());
            System.out.println("CDR saved to: " + cdrFilePath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error generating error CDR: " + e.getMessage());
        }
    }
    
    private void generateCDR(UserCall call, long billableMinutes, long durationSeconds, 
                           double callCost, double finalBalance, String clearingReason) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            
            // Calculate actual minutes and seconds for the CDR
            long actualMinutes = durationSeconds / 60;
            long remainingSeconds = durationSeconds % 60;
            
            String cdrLine = String.format("%s, %s, %s, %d:%02d, %d, %s, %.2f, %.2f\n",
                call.msisdn,
                call.startTime.format(formatter),
                call.endTime.format(formatter),
                actualMinutes,
                remainingSeconds,
                billableMinutes,
                clearingReason,
                callCost,
                finalBalance);
            
            // Get the full path to the CDR file
            Path cdrFilePath = Paths.get(CDR_DIR, CDR_FILE_NAME);
            
            // Append CDR to file
            Files.write(cdrFilePath, cdrLine.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            System.out.println("Generating CDR line: " + cdrLine.trim());
            System.out.println("CDR saved to: " + cdrFilePath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error generating CDR: " + e.getMessage());
        }
    }
    
    private void chargeActiveCalls() {
        for (Map.Entry<String, UserCall> entry : activeCalls.entrySet()) {
            UserCall call = entry.getValue();
            String msisdn = entry.getKey();
            
            if (call.active) {
                double currentBalance = userBalances.get(msisdn);
                double newBalance = currentBalance - CHARGE_RATE;
                
                System.out.println("Charging " + msisdn + ": current balance = " + 
                                 currentBalance + " L.E., charge = " + CHARGE_RATE + 
                                 " L.E., new balance = " + newBalance + " L.E.");
                
                if (newBalance <= 0) {
                    System.out.println("User " + msisdn + " ran out of balance, ending call");
                    call.endTime = LocalDateTime.now();
                    
                    // Calculate call duration and cost up to this point
                    long durationSeconds = ChronoUnit.SECONDS.between(call.startTime, call.endTime);
                    long billableMinutes = (durationSeconds + 59) / 60; // Ceiling division
                    
                    // We can only charge what the user has left
                    double callCost = Math.min(currentBalance, billableMinutes * CHARGE_RATE);
                    
                    // Update user balance to zero (can't go negative)
                    double finalBalance = Math.max(0, currentBalance - callCost);
                    userBalances.put(msisdn, finalBalance);
                    
                    // Calculate actual minutes for display/logging
                    long actualMinutes = durationSeconds / 60;
                    long remainingSeconds = durationSeconds % 60;
                    
                    System.out.println("Call terminated due to insufficient funds");
                    System.out.println("Call duration: " + actualMinutes + " minutes, " + remainingSeconds + " seconds");
                    System.out.println("Billable minutes: " + billableMinutes);
                    System.out.println("Call cost: " + callCost + " L.E. (limited by available balance)");
                    System.out.println("Final balance: " + finalBalance + " L.E.");
                    
                    // Send termination message to mobile
                    sendTerminationMessage(msisdn, "Insufficient Balance");
                    
                    // Mark call as inactive and remove from active calls
                    call.active = false;
                    activeCalls.remove(msisdn);
                    
                    // Save call audio to WAV file
                    saveCallAudio(call);
                    
                    // Generate CDR with reason "Insufficient Balance"
                    generateCDR(call, billableMinutes, durationSeconds, callCost, finalBalance, "Insufficient Balance");
                } else {
                    userBalances.put(msisdn, newBalance);
                    call.currentBalance = newBalance;
                    
                    // Calculate and display current call duration
                    LocalDateTime now = LocalDateTime.now();
                    long durationSeconds = ChronoUnit.SECONDS.between(call.startTime, now);
                    long minutes = durationSeconds / 60;
                    long seconds = durationSeconds % 60;
                    System.out.println("Call with " + msisdn + " in progress: " + 
                                     minutes + ":" + String.format("%02d", seconds) + 
                                     ", charged for " + (minutes + 1) + " minutes so far");
                }
            }
        }
    }
    
    private void sendTerminationMessage(String msisdn, String reason) {
        try {
            Socket socket = clientSockets.get(msisdn);
            if (socket != null && !socket.isClosed()) {
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                String terminationMessage = "TERMINATE_CALL:" + reason;
                out.println(terminationMessage);
                System.out.println("Sent termination message to mobile " + msisdn + ": " + terminationMessage);
            } else {
                System.out.println("No active socket found for MSISDN " + msisdn + " to send termination message");
            }
        } catch (Exception e) {
            System.err.println("Error sending termination message: " + e.getMessage());
        }
    }
    
    private void handleVoiceData() {
        try {
            // Setup audio output
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, 
                                               CHANNELS, SIGNED, BIG_ENDIAN);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("Line not supported");
                return;
            }
            
            System.out.println("Audio playback system initialized successfully");
            
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, BUFFER_SIZE * 5); // Larger buffer to prevent underruns
            line.start();
            
            System.out.println("Audio playback started");
            
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            System.out.println("Voice data handler ready - waiting for packets on UDP port " + UDP_PORT);
            int packetCount = 0;
            int playedPacketCount = 0;
            
            while (true) {
                // Clear buffer before receive
                Arrays.fill(buffer, (byte) 0);
                packet.setLength(buffer.length);
                
                voiceSocket.receive(packet);
                packetCount++;
                
                if (packetCount % 20 == 0) {
                    System.out.println("Received " + packetCount + " packets, played " + 
                                      playedPacketCount + " packets");
                }
                
                // Get packet source address for logging
                String sourceAddress = packet.getAddress().getHostAddress();
                int sourcePort = packet.getPort();
                
                // Check if packet is from an active call - match by IP address only
                boolean isActiveCall = false;
                String activeMsisdn = null;
                UserCall activeCall = null;
                
                // Display all active calls to debug
                if (packetCount == 1 || packetCount % 50 == 0) {
                    System.out.println("Current active calls: " + activeCalls.size());
                    for (Map.Entry<String, UserCall> entry : activeCalls.entrySet()) {
                        UserCall call = entry.getValue();
                        System.out.println("  - MSISDN: " + call.msisdn + 
                                         ", IP: " + call.address.getHostAddress() +
                                         ", Active: " + call.active);
                    }
                }
                
                for (UserCall call : activeCalls.values()) {
                    // Compare only IP addresses, not ports - simpler logic for testing
                    if (call.active && call.address.getHostAddress().equals(packet.getAddress().getHostAddress())) {
                        isActiveCall = true;
                        activeMsisdn = call.msisdn;
                        activeCall = call;
                        
                        // Update port if it changed
                        if (call.port != packet.getPort()) {
                            System.out.println("Updating source port for " + call.msisdn + 
                                             " from " + call.port + " to " + packet.getPort());
                            call.port = packet.getPort();
                        }
                        
                        // First packet from this call
                        if (playedPacketCount == 0) {
                            System.out.println("First packet from " + activeMsisdn + 
                                              " at " + sourceAddress + ":" + sourcePort);
                        }
                        break;
                    }
                }
                
                // Only play audio if from active call
                if (isActiveCall && activeCall != null) {
                    // If we're getting audio data, play it through the speakers
                    if (packet.getLength() > 0) {
                        // Store a copy of the audio data for recording
                        byte[] audioCopy = Arrays.copyOf(packet.getData(), packet.getLength());
                        activeCall.audioData.write(audioCopy, 0, audioCopy.length);
                        
                        // Play the audio
                        line.write(packet.getData(), 0, packet.getLength());
                        playedPacketCount++;
                        
                        if (playedPacketCount == 1) {
                            System.out.println("Started playing audio from first packet");
                        }
                        
                        if (playedPacketCount % 50 == 0) {
                            System.out.println("Playing audio from MSISDN: " + activeMsisdn + 
                                              " at " + sourceAddress + ":" + sourcePort +
                                              " (packet size: " + packet.getLength() + " bytes)");
                        }
                    }
                } else {
                    if (packetCount % 20 == 0) {
                        System.out.println("Ignoring packet from " + sourceAddress + ":" + sourcePort + 
                                          " - not from active call");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling voice data: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void cleanup() {
        System.out.println("Cleaning up MSC resources...");
        
        try {
            // Close all client sockets
            for (Socket socket : clientSockets.values()) {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
            
            // Close signaling server
            if (signalingServer != null && !signalingServer.isClosed()) {
                signalingServer.close();
            }
            
            // Close voice socket
            if (voiceSocket != null && !voiceSocket.isClosed()) {
                voiceSocket.close();
            }
            
            // Shutdown scheduler
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
            
            // Handle any active calls that weren't properly ended
            for (Map.Entry<String, UserCall> entry : activeCalls.entrySet()) {
                UserCall call = entry.getValue();
                if (call.active) {
                    call.endTime = LocalDateTime.now();
                    
                    // Save any collected audio data
                    saveCallAudio(call);
                    
                    // Generate CDR
                    long durationSeconds = ChronoUnit.SECONDS.between(call.startTime, call.endTime);
                    long billableMinutes = (durationSeconds + 59) / 60; // Ceiling division
                    double callCost = billableMinutes * CHARGE_RATE;
                    double currentBalance = userBalances.get(call.msisdn);
                    callCost = Math.min(callCost, currentBalance);
                    double newBalance = currentBalance - callCost;
                    
                    generateCDR(call, billableMinutes, durationSeconds, callCost, newBalance, "MSC Shutdown");
                }
            }
            
            System.out.println("MSC cleanup complete");
        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        MSC msc = new MSC();
        msc.start();
    }
} 