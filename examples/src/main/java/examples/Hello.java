package examples;

public final class Hello {
    public static void main(String[] args) {
        System.out.println(
            "vendor=" + System.getProperty("java.vendor")
                + " version=" + System.getProperty("java.version")
                + " home=" + System.getProperty("java.home"));
    }
}
