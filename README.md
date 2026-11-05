# Nhome (Android)

Aplicație-fereastră pentru Nhome. La pornire și la fiecare schimbare de rețea caută serverul Nhome în Wi-Fi-ul de acasă (`http://<ip>:8788`, verificat prin `/api/health` → „Nhome Client”). Dacă îl găsește, încarcă interfața direct de pe el; altfel deschide `https://nhome.nicu.xyz`.

Nu conține chei, parole sau date despre casă. Interfața și toată logica sunt pe serverul Nhome.

**Instalare:** Releases → ultimul `Nhome-x.y.z.apk` → deschide pe telefon → Instalează.

Notă: asocierea telefonului se face o dată pentru adresa locală și o dată pentru adresa publică (sunt origini diferite în WebView).
