# Política de privacidade do Tocaí

*Última atualização: novembro de 2026*

**Resumo: os dados do teu calendário nunca saem do telemóvel.**

O Tocaí não tem servidores, contas, publicidade nem estatísticas de utilização.
A permissão de Internet é usada **só** para as atualizações da app (ver abaixo).

## O que a app lê

- **Calendário** (`READ_CALENDAR`): lê os calendários e os eventos que já estão sincronizados
  no telemóvel (título, hora, local, descrição, cor e se recusaste o convite). Só os lê; nunca os altera.
  Serve apenas para saber quando deve tocar cada despertador e para os mostrar no ecrã.

## O que a app guarda

Tudo fica nas definições internas da app, no próprio telemóvel:

- os calendários que escolheste e os minutos de antecedência;
- as palavras-chave para incluir ou excluir eventos;
- os eventos que ligaste ou desligaste à mão (identificador do evento e hora);
- os identificadores e horas dos eventos já vistos, para avisar só das marcações novas;
- o som, a vibração e as restantes preferências.

Estes dados apagam-se quando limpas os dados da app ou a desinstalas.

## Atualizações (único uso da Internet)

Para saber se há versão nova, o Tocaí faz um pedido anónimo à API pública do GitHub
(`https://api.github.com/repos/3devangelist/tocai/releases/latest`) e, se escolheres
**Atualizar agora**, descarrega o ficheiro `Tocai.apk` dessa versão. Estes pedidos não levam
nenhum dado teu nem do calendário; o GitHub vê apenas o endereço IP, como em qualquer visita
a um site (ver a [política de privacidade do GitHub](https://docs.github.com/pt/site-policy/privacy-policies/github-general-privacy-statement)).

- A verificação automática (uma vez por dia, com rede) pode ser desligada em
  **Definições → Verificar atualizações automaticamente**.
- A permissão **Instalar apps desconhecidas** só é usada para instalar a versão nova que
  descarregaste, e o Android pede sempre a tua confirmação.
- Em alternativa, podes desligar a verificação e usar o
  [Obtainium](https://github.com/ImranR98/Obtainium) para acompanhar as Releases do repositório.

## Para que servem as outras permissões

| Permissão | Para quê |
|---|---|
| Notificações | Mostrar o despertador a tocar, com os botões **Parar** e **Adiar** |
| Alarmes exatos | Tocar ao minuto certo, mesmo com o telemóvel em repouso |
| Ecrã inteiro | Acender o ecrã e mostrar o despertador por cima do bloqueio |
| Iniciar no arranque | Voltar a agendar os despertadores depois de reiniciares o telemóvel |
| Vibração e manter ativo | Vibrar e não deixar o telemóvel adormecer enquanto toca |
| Serviço em primeiro plano | Manter o som a tocar até carregares em **Parar** |
| Otimização da bateria | Pedir para não ser travada pelo sistema (opcional) |
| Definir alarme | Criar, se quiseres, um alarme na app **Relógio** |
| Internet | Procurar e descarregar atualizações do Tocaí no GitHub (nada mais) |
| Instalar apps | Instalar a atualização descarregada, sempre com a tua confirmação |

## Partilha com terceiros

Nenhuma. A app não contém bibliotecas de terceiros que recolham dados.

## Contacto

Dúvidas ou sugestões: abre uma *issue* no repositório do projeto.
