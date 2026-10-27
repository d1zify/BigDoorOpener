# Big Door Opener

This is an add-on for the amazing plugin [Big Doors](https://www.spigotmc.org/resources/58669/).

It allows you to create doors, which open and close based on various conditions.


Please see the [wiki](https://github.com/eldoriarpg/BigDoorOpener/wiki) for further Information.

The latest stable release can be found on [spigot](https://www.spigotmc.org/resources/80805/)

## KINGDOM Paper 1.21.8 build

This fork provides the BigDoorsOpener JAR used with Paper 1.21.8 and Java 21.
The 2.6.1 build requires Paper 1.21.8 and Java 21. Existing global doors keep
their original behavior; individual doors are enabled explicitly.

Install these plugins in the server's `plugins/` directory, then restart:

1. [BigDoors 0.1.8.70](https://github.com/PimvanderLoos/BigDoors/releases/tag/0.1.8.70) by Pim van der Loos (GPL-3.0).
2. [BigDoorsPhysics 2.4.4](https://modrinth.com/plugin/bigdoorsphysics) by bonn2 (AGPL-3.0).
3. BigDoorsOpener 2.6.1, built with `gradlew build` (AGPL-3.0).

If you use JavaScript conditions in BigDoorsOpener, install a NashornJS script
engine as well. The three plugins above were verified to enable together in a
local Paper test server; door behavior must still be checked in game.

## Portas individuais (2.6.1)

Um administrador com `bdo.command.personal` pode configurar uma porta existente:

```text
/bdo personal <id> permission kingdom.porta.exemplo
/bdo personal <id> player Pepo_Kingdom
/bdo personal <id> info
/bdo personal <id> off
```

Escolha **um** modo de acesso: permissão ou jogador. Um comando substitui a
regra anterior, não acrescenta outro jogador. O nome deve ser de uma conta já
conhecida pelo servidor; também é possível informar seu UUID. Não há bypass
automático de OP para uma lista nominal. Permissões seguem o provedor instalado.

A porta deve estar fechada, parada e com seus chunks carregados. Nenhuma porta
é convertida automaticamente. Assim que a permissão concedida pela quest fica
efetiva, o jogador autorizado vê a animação privada de elevação e pode passar,
sem precisar se aproximar. Os outros continuam
vendo os blocos fechados e têm a passagem bloqueada pelo servidor. Esta animação
é própria do modo individual: não reproduz a rotação original de uma ponte ou
porta articulada. Não depende de textura, ProtocolLib ou PacketEvents.

A permissão é reavaliada a cada tick. A quest pode concedê-la no ponto desejado
da cutscene: não precisa de comando adicional para abrir. A elevação dura 20 ticks
(cerca de 1 segundo a 20 TPS). Remover a permissão fecha a porta só para o jogador.
A animação só pode ser vista com o mundo e os chunks da porta carregados e enviados
ao cliente. Não há carregamento forçado nem limite adicional de 5 ou 48 blocos.
Se a permissão já existia ao chegar, a porta será apresentada como autorizada;
essa checagem não mantém um histórico de conclusão ou da cutscene.

No modo individual, as condições antigas de horário, itens e outras avaliações
não são executadas. Elas permanecem salvas e voltam a valer com `off`. O bloqueio
administrativo do BigDoors continua sendo respeitado.

### Persistência e limites

- O snapshot é salvo em `plugins/BigDoorsOpener/personal-doors.yml` **antes** de
  abrir o espaço físico. Inclua esse arquivo e seu `.bak` nos backups do mundo.
- Enquanto ativo, o espaço físico é ar; a visualização fechada é enviada por
  jogador, com proteção de movimento, veículos, projéteis e ataques diretos.
- Use `off` antes de remover o addon. Ele restaura os blocos originais; também
  há restauração ao descarregar chunks/mundos e ao desligar normalmente.
- Não edite coordenadas, snapshots ou o banco do BigDoors manualmente enquanto
  o modo individual estiver ativo. Remoções externas conflitantes bloqueiam o
  acesso e são registradas sem sobrescrever o bloco inesperado.
- Limite de 512 blocos de volume por porta. Inventários, entidades de bloco e
  líquidos são recusados. A colisão das células sólidas é conservadora, cúbica.
- Até 128 sessões e 2.048 blocos de animação simultâneos. Ao atingir o orçamento
  visual, a abertura é instantânea, mas a regra de acesso permanece.
- Chunks não são mantidos carregados. O antigo `open-distance` é ignorado para
  que a abertura automática não dependa de aproximação.
- Não substitui proteção de arena: teleportes legítimos para o outro lado e
  habilidades de terceiros que ignoram colisão exigem proteção própria.

### Validação obrigatória no jogo

O build inclui oito testes automatizados de acesso, snapshot e colisão. Eles não
comprovam a renderização no cliente. Antes de usar em área pública:

1. Testar simultaneamente com dois jogadores sem OP, somente um autorizado.
2. Com a porta visível a mais de 5 e de 48 blocos, conceder a permissão na cutscene;
   conferir abertura sem andar, fechamento, passagem bloqueada e revogação.
3. Reconectar, descarregar/carregar o chunk e reiniciar de forma controlada.
4. Testar veículo, projétil, restauração com `off` e preservação das portas globais.

Não use reload geral ou hot-load para instalar o JAR. O operador deve reiniciar
o servidor pelo painel após salvar backup do JAR, configuração, snapshot e banco
do BigDoors. Instale somente `build/libs/BigDoorsOpener-2.6.1-all.jar`, sem manter
duas versões do addon em `plugins/`.
