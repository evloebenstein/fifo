## 📋 Descripción del Cambio

Explica de forma concisa qué problema soluciona este Pull Request o qué característica incorpora a Fifo.

## 🎯 Tipo de Cambio

Marca con una `[x]` las casillas aplicables:
- [ ] 🐛 Corrección de bug (fix que no rompe compatibilidad previa)
- [ ] ✨ Nueva característica o skill de voz (feature)
- [ ] ♿ Mejora de accesibilidad (UI de alto contraste, tipografía, TalkBack)
- [ ] ⚡ Optimización de rendimiento / latencia de audio o BLE
- [ ] 📝 Documentación o comentarios de código
- [ ] 🔧 Refactorización o mantenimiento

## 🧪 Pruebas Realizadas

Describe las pruebas que verifican el correcto funcionamiento del cambio:
- [ ] Compilación exitosa en Android (`./gradlew assembleDebug`)
- [ ] Probado en dispositivo físico Android (especificar modelo): ____________
- [ ] Probado con interacción de voz ("FIFO", comandos de llamada, etc.)
- [ ] Verificado que no haya regresiones en segundo plano ni fugas de audio/BLE

## ✅ Checklist de Calidad

- [ ] Mi código sigue las guías descritas en [CONTRIBUTING.md](../CONTRIBUTING.md)
- [ ] Se respetan los principios de dignidad y accesibilidad de [PRODUCT.md](../PRODUCT.md)
- [ ] He actualizado la documentación si los cambios lo ameritan
- [ ] No se incluyen claves de API ni secretos privados en los commits
