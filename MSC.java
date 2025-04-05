import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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
    
    // Encryption related fields
    private KeyPair rsaKeyPair;
    private Map<String, SecretKey> clientKeys; // Store AES keys for each client
    private Map<String, byte[]> clientIVs;    // Store IVs for each client
    
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
        clientKeys = new ConcurrentHashMap<>();
        clientIVs = new ConcurrentHashMap<>();
        scheduler = Executors.newScheduledThreadPool(2);
        
        // Initialize some user balances (in a real system, would be loaded from a database)
        userBalances.put("01223456789", 100.0);
        userBalances.put("01234567890", 50.0);
        userBalances.put("01112223333", 25.0);
        userBalances.put("01020053936", 5.0);
        
        // Create required directories if they don't exist
        createDirectoryIfNotExists(VOICE_DIR);
        createDirectoryIfNotExists(CDR_DIR);
        
        // Initialize the RSA key pair for secure key exchange
        try {
            rsaKeyPair = SecurityUtils.generateRSAKeyPair();
            System.out.println("Generated RSA key pair for secure communications");
        } catch (Exception e) {
            System.err.println("Error generating RSA key pair: " + e.getMessage());
            e.printStackTrace();
        }
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
        String clientAddress = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
        String connectedMsisdn = null;
        
        try {
            InputStream is = clientSocket.getInputStream();
            OutputStream os = clientSocket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            PrintWriter out = new PrintWriter(os, true);
            
            // Send the server's public key to enable secure key exchange
            String publicKeyStr = SecurityUtils.keyToString(rsaKeyPair.getPublic());
            out.println("PUBLIC_KEY:" + publicKeyStr);
            System.out.println("Sent public key to client for secure key exchange");
            
            String message;
            
            // Wait for the client to send its encrypted AES key
            while ((message = in.readLine()) != null) {
                if (message.startsWith("AES_KEY:")) {
                    // Client is sending the AES key encrypted with our public key
                    String encryptedKeyStr = message.substring("AES_KEY:".length());
                    byte[] encryptedKey = Base64.getDecoder().decode(encryptedKeyStr);
                    
                    // Process the next line which should be the IV
                    String ivLine = in.readLine();
                    if (ivLine != null && ivLine.startsWith("IV:")) {
                        String ivStr = ivLine.substring("IV:".length());
                        byte[] iv = Base64.getDecoder().decode(ivStr);
                        
                        // Decrypt the AES key using our private key
                        byte[] decryptedKeyBytes = SecurityUtils.decryptRSA(encryptedKey, rsaKeyPair.getPrivate());
                        SecretKey clientKey = new SecretKeySpec(decryptedKeyBytes, 0, decryptedKeyBytes.length, "AES");
                        
                        // Temporarily store the key - will associate with MSISDN once we receive it
                        String tempKeyId = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
                        clientKeys.put(tempKeyId, clientKey);
                        clientIVs.put(tempKeyId, iv);
                        
                        System.out.println("Received and decrypted AES key and IV from client");
                        
                        // Now ready to receive encrypted messages
                        out.println("READY_FOR_ENCRYPTED");
                    }
                } 
                else if (message.startsWith("ENC:")) {
                    // This is an encrypted message
                    String encryptedStr = message.substring("ENC:".length());
                    
                    // Get the key/IV for this client
                    String tempKeyId = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
                    SecretKey clientKey = clientKeys.get(tempKeyId);
                    byte[] iv = clientIVs.get(tempKeyId);
                    
                    if (clientKey != null && iv != null) {
                        // Decrypt the message
                        String decryptedMsg = SecurityUtils.decryptStringAES(encryptedStr, clientKey, iv);
                        
                        // Process the decrypted message
                        if (decryptedMsg.startsWith("START_CALL:")) {
                            connectedMsisdn = decryptedMsg.substring("START_CALL:".length());
                            
                            // Now associate the key with the MSISDN
                            clientKeys.put(connectedMsisdn, clientKey);
                            clientIVs.put(connectedMsisdn, iv);
                            
                            // Remove the temporary key mapping
                            clientKeys.remove(tempKeyId);
                            clientIVs.remove(tempKeyId);
                            
                            storeClientSocket(connectedMsisdn, clientSocket);
                            handleStartCall(connectedMsisdn, clientSocket.getInetAddress());
                        } 
                        else if (decryptedMsg.startsWith("END_CALL:")) {
                            connectedMsisdn = decryptedMsg.substring("END_CALL:".length());
                            handleEndCall(connectedMsisdn);
                        }
                    } else {
                        System.err.println("Error: No encryption key available for client");
                    }
                }
                // Support for legacy unencrypted communication - can be removed later
                else if (message.startsWith("START_CALL:") || message.startsWith("END_CALL:")) {
                    System.out.println("WARNING: Received unencrypted message: " + message);
                    if (message.startsWith("START_CALL:")) {
                        connectedMsisdn = message.substring("START_CALL:".length());
                        storeClientSocket(connectedMsisdn, clientSocket);
                        handleStartCall(connectedMsisdn, clientSocket.getInetAddress());
                    } else if (message.startsWith("END_CALL:")) {
                        connectedMsisdn = message.substring("END_CALL:".length());
                        handleEndCall(connectedMsisdn);
                    }
                }
            }
            
            // If we reach here, the client closed the connection normally
            System.out.println("Client " + clientAddress + " disconnected normally");
            
        } catch (SocketException e) {
            // Handle connection reset errors more gracefully
            System.out.println("Client " + clientAddress + " connection lost: " + e.getMessage());
            
            // If we know which MSISDN was connected, end the call properly
            if (connectedMsisdn != null) {
                UserCall call = activeCalls.get(connectedMsisdn);
                if (call != null && call.active) {
                    System.out.println("Ending call for MSISDN " + connectedMsisdn + " due to connection loss");
                    handleEndCall(connectedMsisdn);
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing signaling client from " + clientAddress + ": " + e.getMessage());
            
            // If we know which MSISDN was connected, end the call properly
            if (connectedMsisdn != null) {
                UserCall call = activeCalls.get(connectedMsisdn);
                if (call != null && call.active) {
                    System.out.println("Ending call for MSISDN " + connectedMsisdn + " due to error");
                    handleEndCall(connectedMsisdn);
                }
            }
        } finally {
            try {
                // Clean up temporary keys if they exist
                String tempKeyId = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
                clientKeys.remove(tempKeyId);
                clientIVs.remove(tempKeyId);
                
                // Close the socket if it's still open
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
                
                System.out.println("Client socket cleanup completed for " + clientAddress);
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
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
                
                // Encrypt the message if we have a key for this client
                SecretKey clientKey = clientKeys.get(msisdn);
                byte[] iv = clientIVs.get(msisdn);
                
                if (clientKey != null && iv != null) {
                    // Encrypt and send
                    String encryptedMsg = SecurityUtils.encryptStringAES(terminationMessage, clientKey, iv);
                    out.println("ENC:" + encryptedMsg);
                    System.out.println("Sent encrypted termination message to mobile " + msisdn + ": " + terminationMessage);
                } else {
                    // Fallback to unencrypted
                    out.println(terminationMessage);
                    System.out.println("WARNING: Sent unencrypted termination message to mobile " + msisdn + ": " + terminationMessage);
                }
            } else {
                System.out.println("No active socket found for MSISDN " + msisdn + " to send termination message");
            }
        } catch (Exception e) {
            System.err.println("Error sending termination message: " + e.getMessage());
            e.printStackTrace();
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
            
            byte[] buffer = new byte[BUFFER_SIZE * 2]; // Increase buffer size for encrypted data
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
                    // If we're getting audio data, decrypt and play it
                    if (packet.getLength() > 0) {
                        byte[] audioData = packet.getData();
                        int audioLength = packet.getLength();
                        
                        // Get the encryption key for this MSISDN
                        SecretKey clientKey = clientKeys.get(activeMsisdn);
                        byte[] iv = clientIVs.get(activeMsisdn);
                        
                        if (clientKey != null && iv != null) {
                            try {
                                // Decrypt the audio data using the specialized audio decryption method
                                byte[] decryptedData = SecurityUtils.decryptAudioAES(
                                    Arrays.copyOf(audioData, audioLength), clientKey, iv, 0);
                                
                                // Store a copy of the decrypted audio data for recording
                                activeCall.audioData.write(decryptedData, 0, decryptedData.length);
                                
                                // Play the decrypted audio
                                line.write(decryptedData, 0, decryptedData.length);
                                playedPacketCount++;
                                
                                if (playedPacketCount == 1) {
                                    System.out.println("Started playing audio from first packet (decrypted)");
                                }
                                
                                if (playedPacketCount % 50 == 0) {
                                    System.out.println("Playing decrypted audio from MSISDN: " + activeMsisdn + 
                                                    " at " + sourceAddress + ":" + sourcePort +
                                                    " (packet size: " + audioLength + " bytes, decrypted size: " 
                                                    + decryptedData.length + " bytes)");
                                }
                            } catch (Exception e) {
                                System.err.println("Error decrypting audio data: " + e.getMessage());
                                
                                // Try to determine if this is an unencrypted legacy packet
                                boolean looksLikeUnencryptedAudio = false;
                                
                                // Audio data typically has alternating positive and negative values
                                // Check a small sample of the data to see if it looks like audio
                                if (audioLength > 10) {
                                    int nonZeroCount = 0;
                                    for (int i = 0; i < Math.min(20, audioLength); i++) {
                                        if (audioData[i] != 0) nonZeroCount++;
                                    }
                                    // If we have some non-zero bytes, it might be unencrypted audio
                                    looksLikeUnencryptedAudio = (nonZeroCount > 5);
                                }
                                
                                if (looksLikeUnencryptedAudio) {
                                    System.out.println("Packet appears to be unencrypted audio, playing in legacy mode");
                                    activeCall.audioData.write(audioData, 0, audioLength);
                                    line.write(audioData, 0, audioLength);
                                    playedPacketCount++;
                                } else {
                                    System.err.println("Audio packet cannot be decrypted or played, skipping");
                                }
                            }
                        } else {
                            // No encryption key, play as-is (for backward compatibility)
                            System.out.println("No encryption key for MSISDN " + activeMsisdn + 
                                            ", playing unencrypted audio");
                            activeCall.audioData.write(audioData, 0, audioLength);
                            line.write(audioData, 0, audioLength);
                            playedPacketCount++;
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