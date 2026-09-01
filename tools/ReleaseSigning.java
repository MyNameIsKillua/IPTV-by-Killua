import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/**
 * Signs a Windows release so the client can refuse an installer that is not ours.
 *
 * Android gets this from the operating system: it will not install an update whose certificate
 * differs from the installed app's. Windows has no such rule - it checks that a signature is
 * valid, never that it belongs to the same publisher as what is already installed - so the desktop
 * client enforces the rule itself, against the public key compiled into it.
 *
 * Run with the single-file launcher, no build step and no dependencies:
 *
 *   java tools/ReleaseSigning.java genkey  "C:/path/outside/the/repo"
 *   java tools/ReleaseSigning.java sign    "C:/path/outside/the/repo/update-signing.key" file.msi
 *   java tools/ReleaseSigning.java verify  PUBLIC_KEY_BASE64 file.msi file.msi.sig
 *
 * The passphrase is read from the console, never from an argument: a command line ends up in shell
 * history, in a process list, and in screenshots.
 */
public final class ReleaseSigning {

    private static final String KEY_FILE = "update-signing.key";
    /** The property `sign` reads when handed a file instead of a person. */
    private static final String PASSPHRASE_PROPERTY = "updateSigningPassphrase";
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MIN_PASSPHRASE = 12;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "genkey" -> genkey(Path.of(require(args, 1, "a directory outside the repository")));
            // A third argument is a properties file holding the passphrase, which is how the
            // release build signs without a person at the keyboard. Two arguments still prompt.
            case "sign" -> sign(
                    Path.of(require(args, 1, "the key file")),
                    Path.of(require(args, 2, "the file to sign")),
                    args.length > 3 ? Path.of(args[3]) : null);
            case "verify" -> verify(
                    require(args, 1, "the public key"),
                    Path.of(require(args, 2, "the file")),
                    Path.of(require(args, 3, "the signature file")));
            default -> usage();
        }
    }

    /** Creates the pair. The private half is written encrypted; the public half is printed. */
    private static void genkey(Path directory) throws Exception {
        Path target = directory.resolve(KEY_FILE);
        if (Files.exists(target)) {
            // Never silently. Replacing this key makes every installed client refuse every future
            // update, and there is no way back except reinstalling by hand.
            System.err.println("REFUSED: " + target + " already exists.");
            System.err.println("Replacing it would make every installed client reject all future updates.");
            System.exit(1);
        }
        String where = directory.toAbsolutePath().toString().toLowerCase();
        if (where.contains("proton drive") || where.contains("onedrive") || where.contains("dropbox")) {
            System.err.println("REFUSED: that directory is inside a synced folder.");
            System.err.println("A signing key there is a signing key on somebody else's servers.");
            System.exit(1);
        }

        char[] passphrase = readNewPassphrase();
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv)
                .doFinal(pair.getPrivate().getEncoded());
        Arrays.fill(passphrase, '\0');

        Properties stored = new Properties();
        stored.setProperty("algorithm", "Ed25519");
        stored.setProperty("iterations", Integer.toString(PBKDF2_ITERATIONS));
        stored.setProperty("salt", base64(salt));
        stored.setProperty("iv", base64(iv));
        stored.setProperty("key", base64(sealed));
        Files.createDirectories(directory);
        try (var out = Files.newOutputStream(target)) {
            stored.store(out, "Killua IPTV update signing key. Private. Never commit, upload or sync this.");
        }

        System.out.println();
        System.out.println("Private key written to: " + target);
        System.out.println("Back it up the way docs/RELEASE.md describes for the Android keystore.");
        System.out.println();
        System.out.println("PUBLIC KEY - safe to share, and belongs in the desktop client:");
        System.out.println();
        System.out.println("  " + base64(pair.getPublic().getEncoded()));
        System.out.println();
    }

    /**
     * Writes file.sig beside the file: a base64 Ed25519 signature over its bytes.
     *
     * @param passphraseFile a properties file holding {@code updateSigningPassphrase}, or null to
     *   ask the console. The file exists so that signing a release is one command rather than a
     *   person typing - exactly what `keystore.properties` already does for the Android key, and
     *   held to the same rules: outside version control, never printed, never in an argument.
     */
    private static void sign(Path keyFile, Path file, Path passphraseFile) throws Exception {
        Properties stored = new Properties();
        try (var in = Files.newInputStream(keyFile)) {
            stored.load(in);
        }
        char[] passphrase = passphraseFile == null
                ? readPassphrase("Passphrase for " + keyFile.getFileName() + ": ")
                : passphraseFrom(passphraseFile);
        byte[] pkcs8 = cipher(
                Cipher.DECRYPT_MODE,
                passphrase,
                unbase64(stored.getProperty("salt")),
                unbase64(stored.getProperty("iv"))).doFinal(unbase64(stored.getProperty("key")));
        Arrays.fill(passphrase, '\0');

        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        Arrays.fill(pkcs8, (byte) 0);

        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(Files.readAllBytes(file));
        Path out = Path.of(file + ".sig");
        Files.writeString(out, base64(signature.sign()) + System.lineSeparator(), StandardCharsets.US_ASCII);

        System.out.println("Signed:    " + file);
        System.out.println("Signature: " + out);
        System.out.println("Upload both as release assets.");
    }

    /** The same check the client makes, so a release can be proved before it is published. */
    private static void verify(String publicKeyBase64, Path file, Path signatureFile) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(unbase64(publicKeyBase64)));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(publicKey);
        signature.update(Files.readAllBytes(file));
        boolean valid = signature.verify(unbase64(Files.readString(signatureFile).trim()));
        System.out.println(valid ? "VALID" : "NOT VALID - do not publish this");
        if (!valid) {
            System.exit(1);
        }
    }

    private static Cipher cipher(int mode, char[] passphrase, byte[] salt, byte[] iv) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256);
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        spec.clearPassword();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        Arrays.fill(derived, (byte) 0);
        return cipher;
    }

    private static char[] readNewPassphrase() {
        Console console = console();
        while (true) {
            char[] first = console.readPassword("Choose a passphrase for the signing key: ");
            char[] again = console.readPassword("Type it again: ");
            boolean ok = Arrays.equals(first, again) && first.length >= MIN_PASSPHRASE;
            Arrays.fill(again, '\0');
            if (ok) {
                return first;
            }
            Arrays.fill(first, '\0');
            System.err.println("They did not match, or it was shorter than " + MIN_PASSPHRASE + " characters.");
        }
    }

    private static char[] readPassphrase(String prompt) {
        return console().readPassword(prompt);
    }

    /**
     * Reads the passphrase out of a properties file, refusing anything that is not one.
     *
     * A missing file and a missing property are both a stop rather than a fallback to prompting:
     * an automated release that silently waits for a keyboard nobody is at looks exactly like one
     * that hung, and the failure would be found by a timeout rather than by a message.
     */
    private static char[] passphraseFrom(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            System.err.println("No such passphrase file: " + file);
            System.exit(1);
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        }
        String value = properties.getProperty(PASSPHRASE_PROPERTY);
        if (value == null || value.isBlank()) {
            System.err.println(file + " has no " + PASSPHRASE_PROPERTY + " property.");
            System.err.println("Add one line:  " + PASSPHRASE_PROPERTY + "=<the passphrase>");
            System.exit(1);
        }
        return value.toCharArray();
    }

    private static Console console() {
        Console console = System.console();
        if (console == null) {
            // Without a console the passphrase would have to come from a pipe or an argument, and
            // both of those are places a passphrase must never be.
            System.err.println("No console available. Run this in a real terminal window.");
            System.exit(1);
        }
        return console;
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unbase64(String text) {
        return Base64.getDecoder().decode(text.trim());
    }

    private static String require(String[] args, int index, String what) {
        if (args.length <= index) {
            System.err.println("Missing argument: " + what);
            usage();
            System.exit(1);
        }
        return args[index];
    }

    private static void usage() {
        System.out.println("Killua IPTV release signing.");
        System.out.println();
        System.out.println("  genkey <directory>                     create the pair, print the public key");
        System.out.println("  sign   <key file> <file> [props]       write <file>.sig");
        System.out.println("  verify <public key> <file> <sig file>  check one before publishing it");
        System.out.println();
        System.out.println("The directory must be outside this repository and outside any synced folder.");
        System.out.println();
        System.out.println("sign asks the console for the passphrase. Give it a properties file holding");
        System.out.println(PASSPHRASE_PROPERTY + " to sign without one - keep that file out of version control.");
    }
}
