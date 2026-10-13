# USB Audio Bridge

Usa o celular Android como placa de som do PC **pelo cabo USB**: o áudio do PC sai no
celular (e dali no fone Bluetooth/com fio) e o microfone do celular vira um microfone no PC.
Feito pra uma máquina sem Bluetooth nem saída analógica.

```
PC (PipeWire)                         cabo USB (adb reverse)              Celular (app)
phone_speaker ── TCP 127.0.0.1:12345 ─────────────────────────────▶ AudioTrack ─▶ fone
phone_mic     ◀─ TCP 127.0.0.1:12346 ◀───────────────────────────── AudioRecord ◀ mic
```

Formato fixo: PCM S16LE 48 kHz (saída estéreo, mic mono). Taxa fixa de propósito:
player com taxa errada deixa o áudio grave (48k tocado a 44.1k).

## App (`src/`, `res/`)

- Saída e mic independentes, cada um com indicador de conexão, **mutar**, **volume** (0–200%)
  e **buffer** (aplica ao soltar o slider).
- Escolha da **saída e entrada do próprio celular** (alto-falante, Bluetooth, fone com fio, USB...).
- Espectrograma ao vivo da entrada e da saída.
- Latência controlada pela fila do `AudioTrack`: mantida entre 1× e 2× o buffer;
  se secar, recoloca o colchão (evita crackle em sequência).
- Portas configuráveis (o `phone-bridge` manda as do PC pro app sozinho).
- Serviço em primeiro plano (tela pode apagar) e opção de manter a tela ligada.

Compila sem Gradle (só Android SDK `platforms;android-34` + `build-tools;34.0.0` + JDK 21):

```sh
./build.sh                       # gera usb-audio-bridge.apk (assinado com keystore de debug)
adb install -r usb-audio-bridge.apk
```

## PC (`pc/`)

```sh
cp pc/pipewire/*.conf ~/.config/pipewire/pipewire.conf.d/
cp pc/bin/* ~/.local/bin/
systemctl --user restart pipewire pipewire-pulse wireplumber
```

- `pc/pipewire/20-phone-mic.conf` — microfone virtual **"Celular (mic via USB)"**.
- `pc/pipewire/21-phone-speaker.conf` — sink `phone_speaker` servido em `127.0.0.1:12345`.
- `pc/pipewire/22-phone-mic-server.conf` — recebe o mic do celular em `127.0.0.1:12346`.

> O parser de config do PipeWire **não aceita caracteres não-ASCII, nem em comentário**.

## Uso

```sh
phone-bridge                # liga tudo (celular desbloqueado, cabo plugado)
phone-bridge stop           # encerra e devolve o áudio pro HDMI
phone-bridge 40 7           # opcional: buffer 40 ms, mic com cancelamento de eco
phone-bridge ports 23456 23457   # troca as portas se 12345/12346 estiverem em uso
```

`phone-bridge` cria os túneis `adb reverse`, abre o app, espera as duas conexões e deixa
`phone_speaker`/`phone_mic` como padrão do sistema. Em jogos/Discord, a entrada é
**"Celular (mic via USB)"**.

`pc/bin/phone-mic` e `pc/bin/phone-speaker` são as versões antigas (mic via `scrcpy`,
saída via Simple Protocol Player), mantidas como alternativa.

## Latência

O Bluetooth (A2DP/AAC) sozinho custa ~230 ms no Android — o app soma poucos ms em cima.
Pra delay baixo de verdade: fone com fio no celular.
