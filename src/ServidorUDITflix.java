import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ServidorUDITflix {

    private static final String[] VIDEOS = {
        "intro.mp4", "teaser.mp4", "episodio-1.mp4", "creditos.mp4"
    };
    private static final int[] DURACIONES = {1500, 2500, 4000, 1000};

    private final int puerto;
    private final Path directorioWeb;
    private final Object bloqueo = new Object();
    private HttpServer servidor;
    private Ejecucion ejecucionActual;
    private Long tiempoSecuencial;
    private Long tiempoConcurrente;

    public ServidorUDITflix(int puerto) throws IOException {
        this.puerto = puerto;
        this.directorioWeb = localizarWeb();
    }

    public void iniciar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", puerto), 0);
        servidor.createContext("/", this::atender);
        servidor.setExecutor(null);
        servidor.start();
    }

    public int getPuerto() {
        return servidor.getAddress().getPort();
    }

    private void atender(HttpExchange intercambio) throws IOException {
        String ruta = intercambio.getRequestURI().getPath();
        String metodo = intercambio.getRequestMethod();

        if ("/api/start".equals(ruta)) {
            if (!"POST".equals(metodo)) {
                responderJson(intercambio, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }
            iniciarEjecucion(intercambio);
            return;
        }
        if ("/api/status".equals(ruta)) {
            if (!"GET".equals(metodo)) {
                responderJson(intercambio, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }
            responderEstado(intercambio);
            return;
        }
        if (!"GET".equals(metodo)) {
            responderTexto(intercambio, 405, "Método no permitido");
            return;
        }
        servirWeb(intercambio, ruta);
    }

    private void iniciarEjecucion(HttpExchange intercambio) throws IOException {
        String modo = parametro(intercambio.getRequestURI().getRawQuery(), "mode");
        if (!"sequential".equals(modo) && !"concurrent".equals(modo)) {
            responderJson(intercambio, 400, "{\"error\":\"Modo no válido\"}");
            return;
        }

        Ejecucion nueva;
        synchronized (bloqueo) {
            if (ejecucionActual != null && ejecucionActual.estaActiva()) {
                responderJson(intercambio, 409, "{\"error\":\"Ya hay un procesamiento en curso\"}");
                return;
            }
            nueva = new Ejecucion(modo);
            ejecucionActual = nueva;
        }

        Thread coordinador = new Thread(() -> ejecutar(nueva), "coordinador-uditflix");
        coordinador.setDaemon(true);
        coordinador.start();
        responderJson(intercambio, 202, "{\"accepted\":true}");
    }

    private void ejecutar(Ejecucion ejecucion) {
        try {
            if ("sequential".equals(ejecucion.modo)) {
                for (Thread trabajador : ejecucion.trabajadores) {
                    trabajador.start();
                    trabajador.join();
                }
            } else {
                for (Thread trabajador : ejecucion.trabajadores) {
                    trabajador.start();
                }
                for (Thread trabajador : ejecucion.trabajadores) {
                    trabajador.join();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ejecucion.fin = System.currentTimeMillis();
        }
    }

    private void responderEstado(HttpExchange intercambio) throws IOException {
        Ejecucion ejecucion;
        synchronized (bloqueo) {
            ejecucion = ejecucionActual;
        }
        if (ejecucion == null) {
            responderJson(intercambio, 200, "{\"running\":false,\"mode\":null,\"elapsedMs\":0,"
                + "\"sequentialMs\":null,\"concurrentMs\":null,\"savedMs\":null,"
                + "\"improvement\":null,\"videos\":[]}");
            return;
        }

        long ahora = ejecucion.fin == 0 ? System.currentTimeMillis() : ejecucion.fin;
        long duracionEjecucion = Math.max(0, ahora - ejecucion.inicio);
        Long secuencial;
        Long concurrente;
        synchronized (bloqueo) {
            if (!ejecucion.estaActiva() && !ejecucion.registrada) {
                if ("sequential".equals(ejecucion.modo)) {
                    tiempoSecuencial = duracionEjecucion;
                } else {
                    tiempoConcurrente = duracionEjecucion;
                }
                ejecucion.registrada = true;
            }
            secuencial = tiempoSecuencial;
            concurrente = tiempoConcurrente;
        }
        Long ahorro = null;
        Double mejora = null;
        if (secuencial != null && concurrente != null && secuencial > 0) {
            ahorro = secuencial - concurrente;
            mejora = ahorro * 100.0 / secuencial;
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"running\":").append(ejecucion.estaActiva())
            .append(",\"mode\":\"").append(ejecucion.modo).append("\"")
            .append(",\"elapsedMs\":").append(duracionEjecucion)
            .append(",\"sequentialMs\":").append(valorJson(secuencial))
            .append(",\"concurrentMs\":").append(valorJson(concurrente))
            .append(",\"savedMs\":").append(valorJson(ahorro))
            .append(",\"improvement\":").append(valorJson(mejora))
            .append(",\"videos\":[");

        for (int i = 0; i < ejecucion.tareas.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            TareaTranscodificacion tarea = ejecucion.tareas.get(i);
            Thread trabajador = ejecucion.trabajadores.get(i);
            json.append("{\"name\":\"").append(VIDEOS[i]).append("\"")
                .append(",\"progress\":").append(tarea.getProgreso())
                .append(",\"state\":\"").append(trabajador.getState()).append("\"")
                .append(",\"startedAt\":").append(valorJson(tarea.getInicio() < 0 ? null : tarea.getInicio()))
                .append(",\"finishedAt\":").append(valorJson(tarea.getFin() < 0 ? null : tarea.getFin()))
                .append('}');
        }
        json.append("]}");
        responderJson(intercambio, 200, json.toString());
    }

    private void servirWeb(HttpExchange intercambio, String ruta) throws IOException {
        String archivo;
        String tipo;
        if ("/".equals(ruta) || "/index.html".equals(ruta)) {
            archivo = "index.html";
            tipo = "text/html; charset=UTF-8";
        } else if ("/estilos.css".equals(ruta)) {
            archivo = "estilos.css";
            tipo = "text/css; charset=UTF-8";
        } else if ("/app.js".equals(ruta)) {
            archivo = "app.js";
            tipo = "application/javascript; charset=UTF-8";
        } else {
            responderTexto(intercambio, 404, "Recurso no encontrado");
            return;
        }

        Path rutaArchivo = directorioWeb.resolve(archivo);
        if (!Files.isRegularFile(rutaArchivo)) {
            responderTexto(intercambio, 404, "No se encuentra el recurso web: " + archivo);
            return;
        }
        byte[] contenido = Files.readAllBytes(rutaArchivo);
        intercambio.getResponseHeaders().set("Content-Type", tipo);
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.sendResponseHeaders(200, contenido.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(contenido);
        }
    }

    private static Path localizarWeb() throws IOException {
        List<Path> inicios = new ArrayList<>();
        try {
            Path ubicacionClases = Paths.get(
                ServidorUDITflix.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath();
            inicios.add(Files.isRegularFile(ubicacionClases)
                ? ubicacionClases.getParent() : ubicacionClases);
        } catch (URISyntaxException e) {
            throw new IOException("No se pudo determinar la ubicación de las clases Java", e);
        }
        inicios.add(Paths.get("").toAbsolutePath());

        for (Path inicio : inicios) {
            for (Path directorio = inicio; directorio != null; directorio = directorio.getParent()) {
                Path candidato = directorio.resolve("web");
                if (Files.isRegularFile(candidato.resolve("index.html"))) {
                    return candidato;
                }
            }
        }
        throw new IOException("No se encontró web\\index.html junto a las clases ni desde "
            + Paths.get("").toAbsolutePath());
    }

    private static String parametro(String query, String nombre) {
        if (query == null) {
            return "";
        }
        for (String parte : query.split("&")) {
            String[] par = parte.split("=", 2);
            if (par.length == 2 && nombre.equals(par[0])) {
                return par[1];
            }
        }
        return "";
    }

    private static String valorJson(Object valor) {
        if (valor == null) {
            return "null";
        }
        if (valor instanceof Double) {
            return String.format(Locale.US, "%.2f", valor);
        }
        return valor.toString();
    }

    private static void responderTexto(HttpExchange intercambio, int estado, String texto)
            throws IOException {
        responder(intercambio, estado, "text/plain; charset=UTF-8", texto);
    }

    private static void responderJson(HttpExchange intercambio, int estado, String texto)
            throws IOException {
        responder(intercambio, estado, "application/json; charset=UTF-8", texto);
    }

    private static void responder(HttpExchange intercambio, int estado, String tipo, String texto)
            throws IOException {
        byte[] contenido = texto.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", tipo);
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.sendResponseHeaders(estado, contenido.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(contenido);
        }
    }

    private static class Ejecucion {
        private final String modo;
        private final long inicio;
        private final List<TareaTranscodificacion> tareas = new ArrayList<>();
        private final List<Thread> trabajadores = new ArrayList<>();
        private volatile long fin;
        private boolean registrada;

        private Ejecucion(String modo) {
            this.modo = modo;
            this.inicio = System.currentTimeMillis();
            for (int i = 0; i < VIDEOS.length; i++) {
                TareaTranscodificacion tarea =
                    new TareaTranscodificacion(VIDEOS[i], DURACIONES[i]);
                tareas.add(tarea);
                trabajadores.add(new Thread(tarea, "transcodificador-" + (i + 1)));
            }
        }

        private boolean estaActiva() {
            return fin == 0;
        }
    }
}