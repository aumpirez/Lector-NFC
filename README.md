# Lector Cédula PY (NFC / ICAO 9303)

App Android que lee el chip sin contacto de la cédula paraguaya con chip
(y también pasaportes electrónicos), usando la librería JMRTD.

## Cómo usar
1. Abrí esta carpeta en Android Studio (File > Open). Dejá que sincronice Gradle.
2. Conectá un celular Android con NFC y presioná Run.
3. Cargá: número de documento, fecha de nacimiento y vencimiento (AAMMDD),
   tal como aparecen en la zona MRZ (las 3 líneas del dorso).
4. Apoyá la cédula en la parte trasera del celular y no la muevas.

## Qué se lee
- DG1: nombres, apellidos, nro. documento, nacionalidad, sexo, fechas.
- DG2: foto (si viene en JPEG; si es JPEG2000 hace falta un decodificador extra).

## Notas
- El chip está protegido: sin los datos de la MRZ no se puede abrir (BAC/PACE).
- El "número de documento" de la MRZ puede ser distinto al número de cédula.
- La huella (DG3) está protegida con certificados del gobierno: no es legible.
