import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

public class Mobile {
    private static final int SAMPLE_RATE = 44100;
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1;
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    private static final int PORT = 5011;
    private static final String MSC_HOST = "localhost";
    private static final int SIGNALING_PORT = 5011;
    private static final int BUFFER_SIZE = 1024;
    private static final boolean TEST_MODE = false; // Set to true to use test tones instead of microphone
    
    private DatagramSocket socket;
    private String msisdn;
    private Socket signalingSocket;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = true;
    private int packetsSent = 0;
    private int selectedMicIndex = -1; // -1 means use default mic
    
    public Mobile(String msisdn) {
        this.msisdn = msisdn;
        this.scheduler = Executors.newScheduledThreadPool(2); // Increased to 2 for signaling listener
    }
    
    public Mobile(String msisdn, int micIndex) {
        this(msisdn);
        this.selectedMicIndex = micIndex;
    }
    
    // Generate a simple sine wave tone for testing
    private byte[] generateTestTone(double frequency, int sampleRate, double durationSecs) {
        int numSamples = (int) (durationSecs * sampleRate);
        byte[] buffer = new byte[numSamples * 2]; // 16-bit samples = 2 bytes per sample
        
        double amplitude = 32760; // Just below max for 16-bit
        double angularFreq = 2.0 * Math.PI * frequency / sampleRate;
        
        for (int i = 0; i < numSamples; i++) {
            double sinValue = Math.sin(angularFreq * i);
            short sample = (short) (amplitude * sinValue);
            
            // Convert short to bytes (little-endian)
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        
        return buffer;
    }
    
    public void start() {
        TargetDataLine line = null;
        try {
            System.out.println("Starting voice call as MSISDN " + msisdn);
            
            // Connect to MSC for signaling
            signalingSocket = new Socket(MSC_HOST, SIGNALING_PORT);
            PrintWriter out = new PrintWriter(signalingSocket.getOutputStream(), true);
            
            // Send start call signaling
            out.println("START_CALL:" + msisdn);
            System.out.println("Sent start call signaling to MSC at " + MSC_HOST + ":" + SIGNALING_PORT);
            
            // Setup reader to receive responses from MSC
            startSignalingListener();
            
            // Setup UDP socket for voice data
            socket = new DatagramSocket();
            InetAddress address = InetAddress.getByName(MSC_HOST);
            System.out.println("Created UDP socket from port " + socket.getLocalPort() + 
                              " to " + MSC_HOST + ":" + PORT);
            
            // Start minute counter task
            final long startTime = System.currentTimeMillis();
            scheduler.scheduleAtFixedRate(() -> {
                long elapsedMinutes = (System.currentTimeMillis() - startTime) / (1000 * 60);
                if (running) {
                    System.out.println(elapsedMinutes + " minutes elapsed, sent " + packetsSent + " packets");
                }
            }, 1, 1, TimeUnit.MINUTES);
            
            // Register shutdown hook to send END_CALL when application stops
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("Shutting down gracefully...");
                    running = false;
                    
                    // Allow main thread to exit the loop
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                    
                    if (signalingSocket != null && !signalingSocket.isClosed()) {
                        try {
                            PrintWriter shutdownOut = new PrintWriter(signalingSocket.getOutputStream(), true);
                            shutdownOut.println("END_CALL:" + msisdn);
                            shutdownOut.close();
                        } catch (IOException e) {
                            // Ignore, we're shutting down
                        } finally {
                            try {
                                signalingSocket.close();
                            } catch (IOException e) {
                                // Ignore
                            }
                        }
                    }
                    
                    if (scheduler != null) {
                        scheduler.shutdownNow();
                    }
                    
                    if (line != null && line.isOpen()) {
                        line.stop();
                        line.close();
                    }
                    
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                    
                    System.out.println("Cleanup complete");
                } catch (Exception e) {
                    System.err.println("Error during shutdown: " + e.getMessage());
                }
            }));
            
            if (TEST_MODE) {
                System.out.println("Running in TEST MODE - sending test tones");
                runTestMode(address);
            } else {
                System.out.println("Running in MICROPHONE MODE - capturing actual audio");
                
                // Check for available microphones and print them
                List<MixerInfo> mics = listAvailableMicrophones();
                
                runMicrophoneMode(address, mics);
            }
        } catch (ConnectException e) {
            System.err.println("Connection refused: MSC server is not running or is not reachable");
            System.err.println("Make sure the MSC application is running before starting the Mobile application");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup resources in case we exit through an exception
            cleanup(line);
        }
    }
    
    private void startSignalingListener() {
        Thread listenerThread = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(signalingSocket.getInputStream()));
                String message;
                
                while (running && (message = in.readLine()) != null) {
                    System.out.println("Received from MSC: " + message);
                    
                    if (message.startsWith("TERMINATE_CALL:")) {
                        String reason = "Unknown reason";
                        if (message.contains(":")) {
                            reason = message.substring(message.indexOf(":") + 1);
                        }
                        System.out.println("\n*** CALL TERMINATED BY MSC: " + reason + " ***");
                        System.out.println("The call has been terminated by the Mobile Switching Center");
                        running = false;
                        
                        // Exit the application after a brief pause
                        scheduler.schedule(() -> {
                            System.exit(0);
                        }, 2, TimeUnit.SECONDS);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error reading from signaling socket: " + e.getMessage());
                    // No need to print error if we're shutting down
                }
            }
        });
        
        listenerThread.setDaemon(true);
        listenerThread.start();
        System.out.println("Started signaling listener to receive messages from MSC");
    }
    
    private static class MixerInfo {
        Mixer.Info info;
        int index;
        
        public MixerInfo(Mixer.Info info, int index) {
            this.info = info;
            this.index = index;
        }
    }
    
    private List<MixerInfo> listAvailableMicrophones() {
        List<MixerInfo> availableMics = new ArrayList<>();
        try {
            System.out.println("=== Available Audio Capture Devices ===");
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            boolean foundMixers = false;
            int micIndex = 0;
            
            for (Mixer.Info info : mixerInfos) {
                Mixer mixer = AudioSystem.getMixer(info);
                Line.Info[] lineInfos = mixer.getTargetLineInfo();
                
                for (Line.Info lineInfo : lineInfos) {
                    if (lineInfo.getLineClass().equals(TargetDataLine.class)) {
                        System.out.println(micIndex + ": " + info.getName() + " - " + info.getDescription());
                        availableMics.add(new MixerInfo(info, micIndex));
                        foundMixers = true;
                        micIndex++;
                        break; // Found a mic line for this mixer
                    }
                }
            }
            
            if (!foundMixers) {
                System.out.println("No microphones found in the system!");
            } else {
                if (selectedMicIndex >= 0 && selectedMicIndex < availableMics.size()) {
                    System.out.println("Selected microphone: " + availableMics.get(selectedMicIndex).info.getName());
                } else if (selectedMicIndex >= 0) {
                    System.out.println("Warning: Selected microphone index " + selectedMicIndex + 
                                     " is out of range. Using default microphone.");
                    selectedMicIndex = -1;
                } else {
                    System.out.println("Using default microphone");
                }
            }
            System.out.println("=======================================");
        } catch (Exception e) {
            System.err.println("Error listing audio devices: " + e.getMessage());
        }
        return availableMics;
    }
    
    private void runTestMode(InetAddress address) throws Exception {
        System.out.println("Generating test tones at frequencies: 440Hz, 880Hz, 1320Hz");
        
        // Generate some test tones at different frequencies
        byte[] tone1 = generateTestTone(440, SAMPLE_RATE, 0.1); // 440Hz (A4) for 0.1 seconds
        byte[] tone2 = generateTestTone(880, SAMPLE_RATE, 0.1); // 880Hz (A5) for 0.1 seconds
        byte[] tone3 = generateTestTone(1320, SAMPLE_RATE, 0.1); // 1320Hz (E6) for 0.1 seconds
        
        // Main loop to send test tones
        while (running) {
            try {
                // Send tone1
                if (running) sendAudioPacket(tone1, address);
                Thread.sleep(10);
                
                // Send tone2
                if (running) sendAudioPacket(tone2, address);
                Thread.sleep(10);
                
                // Send tone3
                if (running) sendAudioPacket(tone3, address);
                Thread.sleep(10);
                
            } catch (InterruptedException e) {
                if (!running) break;
                else throw e;
            }
        }
    }
    
    private void sendAudioPacket(byte[] audioData, InetAddress address) throws IOException {
        // Split the audio data into smaller packets if needed
        int offset = 0;
        int remaining = audioData.length;
        
        while (remaining > 0 && running) {
            int chunkSize = Math.min(remaining, BUFFER_SIZE);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(audioData, offset, chunk, 0, chunkSize);
            
            DatagramPacket packet = new DatagramPacket(chunk, chunkSize, address, PORT);
            if (!socket.isClosed()) {
                socket.send(packet);
                packetsSent++;
                
                if (packetsSent % 100 == 0) {
                    System.out.println("Sent " + packetsSent + " audio packets");
                }
            }
            
            offset += chunkSize;
            remaining -= chunkSize;
        }
    }
    
    private void runMicrophoneMode(InetAddress address, List<MixerInfo> mics) throws Exception {
        // Try to use the default audio capture device
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, 
                                            CHANNELS, SIGNED, BIG_ENDIAN);
        
        // Check if any microphone is explicitly selected
        TargetDataLine line = null;
        
        if (selectedMicIndex >= 0 && selectedMicIndex < mics.size()) {
            // Try to open the selected microphone
            Mixer.Info selectedMixerInfo = mics.get(selectedMicIndex).info;
            try {
                System.out.println("Opening selected microphone: " + selectedMixerInfo.getName());
                Mixer mixer = AudioSystem.getMixer(selectedMixerInfo);
                DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, format);
                line = (TargetDataLine) mixer.getLine(dataLineInfo);
                line.open(format, BUFFER_SIZE * 5);
                line.start();
                System.out.println("Successfully opened selected microphone");
            } catch (Exception e) {
                System.err.println("Error opening selected microphone: " + e.getMessage());
                System.err.println("Will try default microphone instead");
                line = null;
            }
        }
        
        // If selected mic didn't work or none was selected, try default mic
        if (line == null) {
            try {
                System.out.println("Trying to open default microphone");
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format, BUFFER_SIZE * 5);
                line.start();
                System.out.println("Successfully opened default microphone");
            } catch (Exception e) {
                System.err.println("Error opening default microphone: " + e.getMessage());
                System.err.println("Switching to test mode as fallback");
                runTestMode(address);
                return;
            }
        }
        
        // If we got here, we have an open microphone line
        System.out.println("Capturing voice from microphone and sending via UDP...");
        
        // Audio data buffer - make it a bit larger for more consistent audio
        byte[] buffer = new byte[BUFFER_SIZE];
        
        // Main loop to capture and send audio data
        while (running) {
            try {
                // Read from microphone
                int count = line.read(buffer, 0, buffer.length);
                
                // Check if we got valid audio data
                if (count > 0 && running) {
                    // Check if there's actual audio (not just silence)
                    boolean hasAudio = false;
                    for (int i = 0; i < count; i++) {
                        if (buffer[i] != 0) {
                            hasAudio = true;
                            break;
                        }
                    }
                    
                    if (hasAudio || packetsSent % 50 == 0) {
                        // Send via UDP
                        DatagramPacket packet = new DatagramPacket(buffer, count, address, PORT);
                        if (!socket.isClosed()) {
                            socket.send(packet);
                            packetsSent++;
                            
                            if (packetsSent % 50 == 0) {
                                System.out.println("Sent " + packetsSent + " audio packets (size: " + count + " bytes)");
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                if (!running) {
                    // We're shutting down, so this is expected
                    break;
                } else {
                    throw e; // Rethrow if we're not shutting down
                }
            }
        }
        
        // Clean up line in this method
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
    }
    
    private void cleanup(TargetDataLine line) {
        try {
            if (running) {  // Only do cleanup here if we're not already in the shutdown hook
                running = false;
                
                if (signalingSocket != null && !signalingSocket.isClosed()) {
                    try {
                        PrintWriter shutdownOut = new PrintWriter(signalingSocket.getOutputStream(), true);
                        shutdownOut.println("END_CALL:" + msisdn);
                        shutdownOut.close();
                    } catch (IOException e) {
                        // Ignore
                    } finally {
                        try {
                            signalingSocket.close();
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                }
                
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.shutdownNow();
                }
                
                if (line != null && line.isOpen()) {
                    line.stop();
                    line.close();
                }
                
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java Mobile <MSISDN> [microphone-index]");
            System.out.println("Run 'java Mobile list' to see available microphones");
            return;
        }
        
        // Special command to just list available microphones
        if (args[0].equalsIgnoreCase("list")) {
            Mobile tempMobile = new Mobile("temp");
            tempMobile.listAvailableMicrophones();
            return;
        }
        
        String msisdn = args[0];
        Mobile mobile;
        
        if (args.length > 1) {
            try {
                int micIndex = Integer.parseInt(args[1]);
                mobile = new Mobile(msisdn, micIndex);
            } catch (NumberFormatException e) {
                System.out.println("Microphone index must be a number. Use 'java Mobile list' to see available microphones.");
                return;
            }
        } else {
            mobile = new Mobile(msisdn);
        }
        
        mobile.start();
    }
} 