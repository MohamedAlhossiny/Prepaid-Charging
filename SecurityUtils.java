import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SecurityUtils {
    // AES encryption for UDP packets (symmetric encryption)
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    // For audio data that might be small chunks, we use no padding
    private static final String AES_AUDIO_ALGORITHM = "AES/CBC/NoPadding";
    private static final int AES_KEY_SIZE = 256;
    
    // RSA encryption for initial key exchange (asymmetric encryption)
    private static final String RSA_ALGORITHM = "RSA";
    private static final int RSA_KEY_SIZE = 2048;
    
    // Generate RSA key pair for asymmetric encryption
    public static KeyPair generateRSAKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyGen.initialize(RSA_KEY_SIZE);
        return keyGen.generateKeyPair();
    }
    
    // Generate AES key for symmetric encryption
    public static SecretKey generateAESKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        return keyGen.generateKey();
    }
    
    // Generate random IV for AES encryption
    public static byte[] generateIV() {
        byte[] iv = new byte[16]; // 16 bytes for AES
        new SecureRandom().nextBytes(iv);
        return iv;
    }
    
    // Encrypt data with RSA (for key exchange)
    public static byte[] encryptRSA(byte[] data, Key publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(data);
    }
    
    // Decrypt data with RSA (for key exchange)
    public static byte[] decryptRSA(byte[] encryptedData, Key privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encryptedData);
    }
    
    // Encrypt data with AES (for text-based signaling)
    public static byte[] encryptAES(byte[] data, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
        return cipher.doFinal(data);
    }
    
    // Decrypt data with AES (for text-based signaling)
    public static byte[] decryptAES(byte[] encryptedData, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
        return cipher.doFinal(encryptedData);
    }
    
    // Encrypt audio data with AES (for UDP voice data)
    public static byte[] encryptAudioAES(byte[] audioData, SecretKey key, byte[] iv) throws Exception {
        // First 4 bytes will be the original length
        int audioLength = audioData.length;
        
        // Calculate padded length for the audio data (must be multiple of 16 bytes for AES block size)
        int paddedAudioLength = (audioLength + 15) & ~15; // Round up to multiple of 16
        
        // Combined buffer: 4 bytes for length + padded audio data
        byte[] combinedData = new byte[4 + paddedAudioLength];
        
        // Store the original length in the first 4 bytes (big-endian)
        combinedData[0] = (byte) ((audioLength >> 24) & 0xFF);
        combinedData[1] = (byte) ((audioLength >> 16) & 0xFF);
        combinedData[2] = (byte) ((audioLength >> 8) & 0xFF);
        combinedData[3] = (byte) (audioLength & 0xFF);
        
        // Copy audio data after the length bytes
        System.arraycopy(audioData, 0, combinedData, 4, audioLength);
        
        // Use AES in CBC mode with PKCS5Padding (safer choice than NoPadding)
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
        
        // Encrypt and return
        return cipher.doFinal(combinedData);
    }
    
    // Decrypt audio data with AES (for UDP voice data)
    public static byte[] decryptAudioAES(byte[] encryptedData, SecretKey key, byte[] iv, int ignored) throws Exception {
        // Use AES in CBC mode with PKCS5Padding
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
        
        // Decrypt the combined data
        byte[] decryptedCombined;
        try {
            decryptedCombined = cipher.doFinal(encryptedData);
        } catch (Exception e) {
            // If decryption fails, this might be unencrypted data
            throw new IllegalArgumentException("Could not decrypt audio data: " + e.getMessage());
        }
        
        // Extract the original length from the first 4 bytes
        if (decryptedCombined.length < 4) {
            throw new IllegalArgumentException("Decrypted data too short to contain length header");
        }
        
        int originalLength = 
            ((decryptedCombined[0] & 0xFF) << 24) |
            ((decryptedCombined[1] & 0xFF) << 16) |
            ((decryptedCombined[2] & 0xFF) << 8) |
            (decryptedCombined[3] & 0xFF);
        
        // Ensure the extracted length is reasonable to prevent issues
        if (originalLength <= 0 || originalLength > decryptedCombined.length - 4) {
            throw new IllegalArgumentException("Invalid decoded audio length: " + originalLength);
        }
        
        // Create result buffer of the original size
        byte[] result = new byte[originalLength];
        
        // Copy only the original audio data (skipping the length bytes)
        System.arraycopy(decryptedCombined, 4, result, 0, originalLength);
        
        return result;
    }
    
    // Encode key to Base64 string for sending over network
    public static String keyToString(Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
    
    // Decode Base64 string back to AES key
    public static SecretKey stringToAESKey(String keyStr) {
        byte[] decodedKey = Base64.getDecoder().decode(keyStr);
        return new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
    }
    
    // Encode/decode for text-based TCP signaling
    public static String encryptStringAES(String plainText, SecretKey key, byte[] iv) throws Exception {
        byte[] encrypted = encryptAES(plainText.getBytes(), key, iv);
        return Base64.getEncoder().encodeToString(encrypted);
    }
    
    public static String decryptStringAES(String encryptedText, SecretKey key, byte[] iv) throws Exception {
        byte[] encrypted = Base64.getDecoder().decode(encryptedText);
        byte[] decrypted = decryptAES(encrypted, key, iv);
        return new String(decrypted);
    }
} 