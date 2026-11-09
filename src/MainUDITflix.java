import java.awt.Desktop;
import java.net.BindException;
import java.net.URI;

public class MainUDITflix {

    public static void main(String[] args) throws Exception {
        int puerto = 8080;
        ServidorUDITflix servidor = new ServidorUDITflix(puerto);
        try {
            servidor.iniciar();
        } catch (BindException e) {
            servidor = new ServidorUDITflix(0);
            servidor.iniciar();
        }

        String url = "http://localhost:" + servidor.getPuerto();
        System.out.println("UDITflix disponible en " + url);

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(new URI(url));
        }
    }
}
