# Calendário Despertador

App Android que transforma eventos do calendário em **despertadores a sério**:
som de alarme a tocar em loop, vibração e ecrã inteiro, mesmo com o telemóvel
bloqueado ou em "Não incomodar". Não são notificações.

## Como funciona

- Lê o calendário que já está sincronizado no telemóvel (Google Calendar ou outro).
  Não precisa de login nem de internet.
- Escolhes os **calendários automáticos** (por exemplo, *Consultadorias EVOLT*).
  Todos os eventos desses calendários ganham despertador sozinhos.
- Em qualquer outro evento, ligas o despertador à mão no interruptor ao lado.
- Escolhes **quantos minutos antes** toca (5 min por defeito).
- Quando marcam uma consultoria nova, a app deteta a mudança no calendário e agenda o despertador.
  Também volta a agendar tudo depois de reiniciares o telemóvel.
- No ecrã do despertador: **Parar** ou **Adiar 5 min**. Para de tocar sozinho ao fim de 3 minutos.
- O botão ⏰ de cada evento cria antes o alarme na app **Relógio** do telemóvel
  (só para as próximas 24 horas).
- **Testar despertador** faz tocar um alarme daqui a 10 segundos.

## Instalar

1. No telemóvel, abre a página **Releases** deste repositório e descarrega `CalendarioDespertador.apk`.
2. Abre o ficheiro. Se o Android pedir, autoriza "Instalar apps desconhecidas" para o browser ou o gestor de ficheiros.
3. Abre a app e aceita as permissões de **Calendário** e **Notificações**.
4. Se aparecer um aviso amarelo, carrega em **Resolver**. Isto pode pedir:
   - "Alarmes e lembretes"
   - notificações em ecrã inteiro (Android 14 ou mais recente)
5. Carrega em **Testar despertador**.

**Samsung, Xiaomi e outras marcas:** em Definições → Apps → Calendário Despertador → Bateria,
escolhe **Sem restrições**. Assim o sistema não fecha a app.

## Compilar

Cada `push` para `main` compila o APK no GitHub Actions e publica-o em Releases.
A assinatura usa os segredos `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`,
para que as atualizações instalem por cima da versão anterior.
