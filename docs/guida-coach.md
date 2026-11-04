# Coaching Kit — guida per l'allenatore

Coaching Kit trasforma Claude in un assistente allenatore collegato a
intervals.icu. Analizza la settimana di ogni atleta sui dati reali, prepara la
successiva e, **solo dopo il tuo OK**, carica gli allenamenti strutturati, che
arrivano sull'orologio dell'atleta.

Non serve saper programmare né usare il terminale.

## Cosa ti serve

- Un Mac con l'app [Claude](https://claude.com/download) e un piano che
  include Cowork. Windows arriverà più avanti.
- Un account intervals.icu con cui segui i tuoi atleti: ogni atleta deve
  averti dato accesso al suo profilo (dalle impostazioni di intervals.icu,
  condivisione con l'allenatore).

## Installazione (una volta sola, 2 minuti)

1. Apri Claude e vai su **Customize → Plugins**.
2. Scegli **Add marketplace** e scrivi `eldafito/coaching-kit`.
3. Installa **Coaching Kit**.

## Primo avvio (circa 10 minuti)

1. Crea una cartella vuota per il tuo lavoro, per esempio
   `Documenti/Coaching`.
2. In Claude apri **Cowork**, scegli quella cartella e scrivi:
   **"Iniziamo con Coaching Kit"**.
3. Claude ti fa tre domande: il tuo nome, la lingua di lavoro, i tuoi sport.
4. **Collegamento a intervals.icu**: si apre una pagina nel browser.
   - Vai su intervals.icu → **Settings** → **Developer Settings** e copia la
     tua **API key**.
   - Incollala nella pagina e premi **Collega**.
   - La chiave resta sul tuo Mac (nel Portachiavi): non passa dalla chat e
     non finisce nella cartella di lavoro. Non incollarla mai nella chat.
5. Claude ti mostra l'elenco dei tuoi atleti su intervals.icu: scegli con chi
   iniziare. Per ogni atleta legge i dati reali e ti chiede solo quello che
   intervals.icu non sa (obiettivi, gare, giorni disponibili, infortuni).
6. Se vuoi, adatta il tuo metodo in un paio di domande (polarizzato o
   piramidale, zone FC o passo, settimane di scarico...). Puoi farlo anche dopo.
7. Prima review: "Come è andata la settimana di Anna?"

## Come si usa ogni settimana

Scrivi a Claude, come faresti con un assistente:

- *"Review settimanale per Anna"*
- *"Fai la review di tutti, Marco giovedì ha palestra"*
- *"Anna ha una nuova gara a aprile, prepariamo il piano"*
- *"Sposta il lungo di Luca a domenica"*

Claude ti presenta un riassunto della settimana passata e una tabella con la
settimana proposta, poi **si ferma e aspetta il tuo OK**. Puoi chiedere
modifiche finché la settimana non ti convince.

Dopo il tuo OK:

1. Claude carica gli allenamenti e controlla che intervals.icu li abbia letti
   correttamente (passi, tempi, zone).
2. Gli allenamenti nuovi sono **nascosti all'atleta**: controllali sul
   calendario di intervals.icu, poi di' a Claude "rendili visibili".
   Quando ti fidi del flusso, puoi chiedere di caricarli subito visibili.

## Privacy

- I dati dei tuoi atleti restano nella tua cartella di lavoro. Se vuoi un
  backup, usa un posto **privato** (mai una cartella condivisa con link
  pubblico).
- Durante il lavoro i dati che Claude analizza vengono inviati al servizio AI,
  come in ogni conversazione con Claude. Informa i tuoi atleti e chiedi il loro
  consenso: durante il primo avvio Claude ti propone un messaggio già pronto.
- Nessuna modifica arriva su intervals.icu senza il tuo OK esplicito.

## Problemi frequenti

| Cosa succede | Cosa fare |
|---|---|
| Un atleta non compare nell'elenco | Non ti ha ancora dato accesso su intervals.icu: chiediglielo, poi riprova |
| "La chiave non è valida" | Copiala di nuovo da intervals.icu, tutta, dall'account con cui alleni |
| La pagina della chiave non si apre | Claude ti dà un link: aprilo nel browser |
| Hai rigenerato la chiave su intervals.icu | Scrivi "ricollega intervals.icu" |
| Un'attività importata da Strava non ha dati | Limite di intervals.icu: l'atleta deve sincronizzare direttamente dall'orologio (Garmin, Coros, ecc.) |
