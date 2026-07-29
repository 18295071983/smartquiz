import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class generate_key {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = keyGen.generateKeyPair();
        
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" + 
            Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded()) + 
            "\n-----END PRIVATE KEY-----";
        
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + 
            Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded()) + 
            "\n-----END PUBLIC KEY-----";
        
        System.out.println("=== Ed25519 Private Key (PEM format) ===");
        System.out.println(privateKeyPem);
        System.out.println("\n=== Ed25519 Public Key (PEM format) ===");
        System.out.println(publicKeyPem);
    }
}
