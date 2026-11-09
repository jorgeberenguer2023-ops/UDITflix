public class TareaTranscodificacion implements Runnable {

    private final String nombreVideo;
    private final int duracion;
    private volatile int progreso;
    private volatile long inicio;
    private volatile long fin;

    public TareaTranscodificacion(String nombreVideo, int duracion) {
        this.nombreVideo = nombreVideo;
        this.duracion = duracion;
        this.progreso = 0;
        this.inicio = -1;
        this.fin = -1;
    }

    @Override
    public void run() {
        inicio = System.currentTimeMillis();
        System.out.println("Comenzando: " + nombreVideo);

        try {
            long inicioNanos = System.nanoTime();
            long duracionNanos = duracion * 1_000_000L;

            while (true) {
                long transcurrido = System.nanoTime() - inicioNanos;
                if (transcurrido >= duracionNanos) {
                    progreso = 100;
                    break;
                }

                progreso = (int) (transcurrido * 100 / duracionNanos);
                long restanteMs = (duracionNanos - transcurrido) / 1_000_000L;
                Thread.sleep(Math.min(100L, Math.max(1L, restanteMs)));
            }
        } catch (InterruptedException e) {
            System.out.println("Transcodificacion interrumpida: " + nombreVideo);
            Thread.currentThread().interrupt();
        } finally {
            fin = System.currentTimeMillis();
        }

        if (progreso == 100) {
            System.out.println("Finalizado: " + nombreVideo);
        }
    }

    public int getProgreso() {
        return progreso;
    }

    public long getInicio() {
        return inicio;
    }

    public long getFin() {
        return fin;
    }
}
