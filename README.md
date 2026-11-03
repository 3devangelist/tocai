<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0">
  <img src="https://img.shields.io/badge/licen%C3%A7a-MIT-blue" alt="Licença MIT">
  <img src="https://img.shields.io/badge/estado-em%20desenvolvimento-orange" alt="Em desenvolvimento">
</p>

# ⏰ Tocaí

**Os teus eventos do calendário, com despertador a sério.**

> [!NOTE]
> **App pessoal, código aberto.** O Tocaí foi feito para uso pessoal, para não falhar consultas e
> reuniões. O código fica público para quem o quiser usar, adaptar ou melhorar, ao abrigo da licença MIT.

> [!WARNING]
> **Versão em desenvolvimento.** A app ainda está em fase de testes e pode ter erros ou mudar sem aviso.
> Não dependas só dela para compromissos críticos sem antes confirmares com o botão
> **Testar despertador** que funciona no teu telemóvel. Não há suporte garantido, mas sugestões e
> *issues* são bem-vindas.


O Tocaí ("toca aí") lê o calendário que já tens no telemóvel e, para os eventos que escolheres,
agenda um **despertador verdadeiro**: som de alarme em loop, vibração e ecrã inteiro, mesmo com o
telemóvel bloqueado ou em "Não incomodar". Nada de notificações discretas que passam despercebidas.

Ideal para consultas, reuniões com clientes, chamadas importantes ou qualquer compromisso
a que não podes mesmo faltar.

---

## ✨ Funcionalidades

- 📅 **Sem login** — usa os calendários já sincronizados no telemóvel (Google, Outlook, Samsung…).
- 🔔 **Despertador a sério** — som em loop, volume crescente, vibração e ecrã inteiro por cima do bloqueio.
- ⏱️ **Vários avisos por evento** — por exemplo, 15 e 5 minutos antes. Configurável por calendário.
- 🗂️ **Escolhes os calendários** — os eventos desses calendários ganham despertador sozinhos.
- 🔎 **Palavras-chave** — inclui ou exclui eventos pelo título (ex.: excluir "almoço").
- 🙅 **Ignora convites recusados**, se quiseres.
- 🎚️ **Interruptor por evento** — liga ou desliga o despertador de um evento específico.
- 😴 **Parar** e **Adiar** (5, 10 ou 15 min) diretamente no ecrã do alarme.
- 🔄 **Sempre atualizado** — quando alguém marca ou muda um evento, o Tocaí reage sozinho;
  depois de reiniciares o telemóvel, volta a agendar tudo.
- 🕐 **App Relógio** — botão para criar também o alarme na app Relógio do Android.
- 🧩 **Widget e mosaico** das definições rápidas com o próximo despertador.
- 🧪 **Testar despertador** — toca ao fim de 10 segundos, para confirmares que está tudo bem.
- 🌙 **Tema claro e escuro**, interface em português de Portugal.

## 📲 Instalação no telemóvel

1. No telemóvel, abre a página **[Releases](../../releases/latest)** deste repositório.
2. Em **Assets**, toca em **`Tocai.apk`** para o descarregar.
3. Abre o ficheiro descarregado (na notificação ou na app **Ficheiros**).
4. Se o Android avisar que não pode instalar apps desta origem, toca em **Definições**,
   ativa **Permitir desta origem** e volta atrás.
5. Toca em **Instalar** e depois em **Abrir**.
6. Segue o assistente inicial: dá acesso ao calendário, às notificações, aos alarmes exatos
   e ao ecrã inteiro, e escolhe os calendários que devem ter despertador.
7. No fim, toca em **Testar despertador**. Se tocar ao fim de 10 segundos, está tudo pronto. 🎉

As versões seguintes instalam-se por cima e mantêm as tuas definições.

## 🔋 Dicas para a bateria (importante!)

Algumas marcas fecham as apps em segundo plano para poupar bateria, o que pode impedir o
despertador de tocar. O Tocaí pede para ser excluído da otimização, mas em certos telemóveis
é preciso um passo extra:

### Samsung (One UI)
1. **Definições → Bateria → Limites de utilização em segundo plano**.
2. Desliga **Colocar apps não usadas em suspensão**, ou adiciona o Tocaí a **Apps que nunca são suspensas**.
3. Em **Definições → Apps → Tocaí → Bateria**, escolhe **Sem restrições**.

### Xiaomi / Redmi / POCO (MIUI / HyperOS)
1. **Definições → Apps → Gerir apps → Tocaí**.
2. Ativa **Início automático**.
3. Em **Poupança de bateria**, escolhe **Sem restrições**.
4. Nas apps recentes, mantém o Tocaí premido e toca no **cadeado** para não ser fechado.

### Outras marcas (Huawei, OnePlus, Oppo, Realme…)
Procura opções como **Início automático**, **Execução em segundo plano** ou **Otimização da bateria**
e coloca o Tocaí como **não otimizado**. O site [dontkillmyapp.com](https://dontkillmyapp.com)
tem instruções para cada modelo.

## 🔒 Privacidade

**Nada sai do teu telemóvel.** O Tocaí não pede acesso à Internet, não tem contas, servidores,
publicidade nem estatísticas. Só lê o calendário (nunca o altera) e guarda as preferências
localmente. Mais detalhes em [docs/PRIVACIDADE.md](docs/PRIVACIDADE.md).

## 🏗️ Estrutura do projeto

```
app/src/main/java/pt/tocai/app/
  data/      leitura do calendário e definições
  rules/     regras que decidem que eventos têm despertador (Kotlin puro, com testes)
  alarm/     agendamento, receção e o despertador a tocar (serviço + ecrã)
  sync/      sincronização periódica, arranque e canais de notificação
  ui/        ecrã principal, definições, escolha de calendários e assistente inicial
  util/      permissões e formatação de horas
  widget/    widget do ecrã inicial e mosaico das definições rápidas
```

A arquitetura completa, com o contrato entre módulos e o fluxo de dados, está em
**[docs/ARQUITETURA.md](docs/ARQUITETURA.md)**.

### Compilar

O projeto é compilado pelo GitHub Actions (`.github/workflows/build.yml`) a cada *push* para `main`:
corre os testes, gera o APK assinado e publica uma *release* `v1.0.N`.

Localmente, com o Android SDK e o Gradle 8.10.2 instalados:

```bash
gradle :app:testDebugUnitTest :app:assembleRelease
```

Para assinar com a tua chave, define `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e
`KEY_PASSWORD`; sem elas, o APK é assinado com a chave de debug. No GitHub, guarda a chave em
Base64 no segredo `KEYSTORE_B64` e as palavras-passe nos restantes segredos.

**Requisitos:** Android 8.0 (API 26) ou superior.

## 📄 Licença

[MIT](LICENSE) © 2026 Ricardo Pereira
