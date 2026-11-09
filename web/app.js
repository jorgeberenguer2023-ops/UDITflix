const nombres = ["intro.mp4", "teaser.mp4", "episodio-1.mp4", "creditos.mp4"];
const contenedorVideos = document.getElementById("videos");
const botones = [
    document.getElementById("secuencial"),
    document.getElementById("concurrente")
];

contenedorVideos.innerHTML = nombres.map((nombre, indice) => `
    <article class="video">
        <div class="video-superior">
            <span class="nombre">${String(indice + 1).padStart(2, "0")} &nbsp; ${nombre}</span>
            <span id="estado-${indice}" class="estado">NEW</span>
        </div>
        <div class="pista"><div id="barra-${indice}" class="barra"></div></div>
        <div class="video-inferior">
            <div class="marcas-tiempo">
                <span id="inicio-${indice}">Inicio: —</span>
                <span id="fin-${indice}">Fin: —</span>
            </div>
            <span id="porcentaje-${indice}" class="porcentaje">0%</span>
        </div>
    </article>
`).join("");

function mostrarHora(timestamp) {
    return timestamp ? new Date(timestamp).toLocaleTimeString("es-ES") : "—";
}

function mostrarDuracion(milisegundos) {
    return milisegundos == null ? "—" : `${(milisegundos / 1000).toFixed(2)} s`;
}

function actualizarMetricas(estado) {
    document.getElementById("tiempo-secuencial").textContent = mostrarDuracion(estado.sequentialMs);
    document.getElementById("tiempo-concurrente").textContent = mostrarDuracion(estado.concurrentMs);
    document.getElementById("ahorro").textContent = estado.savedMs == null
        ? "—" : `${(estado.savedMs / 1000).toFixed(2)} s`;
    document.getElementById("mejora").textContent = estado.improvement == null
        ? "—" : `${estado.improvement.toFixed(2)}%`;
}

function actualizarInterfaz(estado) {
    estado.videos.forEach((video, indice) => {
        document.getElementById(`barra-${indice}`).style.width = `${video.progress}%`;
        document.getElementById(`porcentaje-${indice}`).textContent = `${video.progress}%`;
        const estadoHilo = document.getElementById(`estado-${indice}`);
        estadoHilo.textContent = video.state;
        estadoHilo.className = `estado ${video.state}`;
        document.getElementById(`inicio-${indice}`).textContent = `Inicio: ${mostrarHora(video.startedAt)}`;
        document.getElementById(`fin-${indice}`).textContent = `Fin: ${mostrarHora(video.finishedAt)}`;
    });

    const modo = document.getElementById("modo-actual");
    modo.textContent = estado.mode === "sequential" ? "SECUENCIAL"
        : estado.mode === "concurrent" ? "CONCURRENTE" : "EN ESPERA";
    modo.classList.toggle("activa", estado.running);
    document.getElementById("aviso").textContent = estado.running
        ? `Procesamiento ${estado.mode === "sequential" ? "secuencial" : "concurrente"} en curso · ${(estado.elapsedMs / 1000).toFixed(1)} s`
        : estado.mode ? "Procesamiento finalizado. Ya puedes ejecutar el otro modo."
            : "Selecciona un modo para iniciar la transcodificación.";
    botones.forEach((boton) => { boton.disabled = estado.running; });
    actualizarMetricas(estado);
}

async function consultarEstado() {
    const respuesta = await fetch("/api/status", { cache: "no-store" });
    if (!respuesta.ok) throw new Error("No se pudo consultar el servidor Java.");
    const estado = await respuesta.json();
    actualizarInterfaz(estado);
    return estado;
}

async function iniciar(modo) {
    botones.forEach((boton) => { boton.disabled = true; });
    document.getElementById("aviso").textContent = "Iniciando tareas Java…";
    try {
        const respuesta = await fetch(`/api/start?mode=${modo}`, { method: "POST" });
        const resultado = await respuesta.json();
        if (!respuesta.ok) throw new Error(resultado.error || "No se pudo iniciar el procesamiento.");
        await consultarEstado();
    } catch (error) {
        document.getElementById("aviso").textContent = error.message;
        botones.forEach((boton) => { boton.disabled = false; });
    }
}

document.getElementById("secuencial").addEventListener("click", () => iniciar("sequential"));
document.getElementById("concurrente").addEventListener("click", () => iniciar("concurrent"));

consultarEstado().catch((error) => {
    document.getElementById("aviso").textContent = error.message;
});
window.setInterval(async () => {
    try {
        await consultarEstado();
    } catch (error) {
        document.getElementById("aviso").textContent = error.message;
    }
}, 250);
