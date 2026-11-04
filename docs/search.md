# Search

NEXORA searches inside documents, list items, pages and news, and only ever shows what the person searching may
read. This page is for the administrators who run it. The design is in [architecture.md](architecture.md#search).

## How it runs

The Docker stack adds two services, and neither publishes a port:

| Service | Job | Memory |
| --- | --- | --- |
| `opensearch` | The full-text index | `NEXORA_OPENSEARCH_HEAP`, 1 GB by default, plus the same again for the file cache |
| `tika` | Reads PDF and Office files, and runs OCR on images and scanned pages | `NEXORA_TIKA_MEMORY`, 4 GB by default |

On first start, or when the index is missing, NEXORA indexes all existing content by itself. You can follow that under
**Central Administration → Search**.

A new or changed document can be found by name within seconds, and by its contents once it has been read. OCR takes
from under a second for a small image to several minutes for a long scan. By default two documents are read at a time
(`NEXORA_SEARCH_EXTRACT_THREADS`).

### Without Docker

`./mvnw spring-boot:run -Dspring-boot.run.profiles=local` uses an embedded Lucene index in `backend/data/search` and a
built-in reader for text and modern Office files. For PDF and OCR in this mode, run Tika and point NEXORA at it:

```sh
docker build -t nexora/tika:local deploy/tika
docker run -p 127.0.0.1:9998:9998 nexora/tika:local -c /opt/tika-config.json
NEXORA_TIKA_URL=http://localhost:9998 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Lucene suits development and small single-server installs. Use OpenSearch in production.

## What people can type

| Query | Finds |
| --- | --- |
| `annual budget` | Items with both words, in any field |
| `"annual budget"` | That exact phrase |
| `budget -draft` or `budget NOT draft` | Items with the first word and without the second |
| `invoice OR receipt` | Items with either word |
| `budg*` | Words starting with `budg` |
| `filetype:pdf` | By extension |
| `type:word` | By kind: `word`, `excel`, `powerpoint`, `pdf`, `image`, `text`, `folder`, `library`, `list`, `item`, `page`, `news`, `site` |
| `author:ionescu` | By who last changed it |
| `site:hr` | Within one site, by its URL name |

Searches ignore case and diacritics, so `invatamant` finds `învățământ`. A single word also matches anywhere inside a
file name, so `2024` finds `budget2024.xlsx`.

## OCR languages

Set `NEXORA_OCR_LANGUAGES` in `.env`, then run `docker compose up -d tika`. Installed languages are `eng`, `fra`, `deu`,
`ita`, `spa`, `jpn` and `ron`.

**Put the main language first.** Tesseract favours the first language: with `eng+ron`, Romanian text loses its
diacritics, while `ron+eng` keeps them. Each extra language slows OCR down. To add a language, add its
`tesseract-ocr-<code>` package to `deploy/tika/Dockerfile`.

Documents that were already read keep their text. After changing languages, **Rebuild index** does not read them again.
To read scanned documents again, clear the `search_text` table for them; **Retry unread documents** only covers
documents listed as unreadable.

To turn OCR off, set `"text-recognizers": []` in `deploy/tika/tika-config.json` and rebuild the `tika` image.

## Administration

**The switch at the top of Central Administration → Search turns search on or off for everyone**, and takes effect at
once on all servers. While it is off:

- the search box disappears and `/api/v1/search` answers 503 with the code `search_disabled`;
- indexing and OCR pause, and changes wait in the queue;
- rebuilding and retrying are unavailable.

Turning it back on works through the queue, so nothing is lost. Results may lag until it finishes. The switch does not
stop the `opensearch` and `tika` containers. To free their memory as well, run `docker compose stop opensearch tika`, and
start them again before turning search on.

The same page also shows the health of both services, the work waiting, and each document that could
not be read and why. Those documents can still be found by name and column values.

- **Rebuild index** empties the index and fills it from the database. Search shows partial results until it finishes.
  Stored text is reused, so nothing is read or OCRed again.
- **Retry unread documents** reads the listed documents again.

If you change the mapping in `OpenSearchEngine`, raise the version in `NEXORA_OPENSEARCH_INDEX`'s default. The new index
is created and filled automatically.

## When something is down

| Failure | What people see | What happens next |
| --- | --- | --- |
| OpenSearch | Name matches only, with a warning on the results page | Changes wait in the queue and are indexed when it returns |
| Tika | Normal search. New documents are found by name but not by contents | Reading resumes within about a minute of Tika returning |
| Both | As for OpenSearch | Both catch up |

The queue lives in PostgreSQL in the same transaction as each change, so an outage or a crash never loses an update.

## Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEXORA_SEARCH_ENGINE` | `lucene` (`opensearch` in Docker) | Which engine to use |
| `NEXORA_OPENSEARCH_URL` | `http://localhost:9200` | Cluster address |
| `NEXORA_OPENSEARCH_USER`, `NEXORA_OPENSEARCH_PASSWORD` | empty | Basic sign-in, for a secured cluster |
| `NEXORA_OPENSEARCH_INDEX` | `nexora-content-v1` | Index name |
| `NEXORA_TIKA_URL` | empty (`http://tika:9998` in Docker) | Tika server. Empty uses the built-in reader |
| `NEXORA_TIKA_TIMEOUT` | `15m` | The longest wait for one document |
| `NEXORA_SEARCH_EXTRACT_THREADS` | `2` | Documents read at the same time |
| `NEXORA_SEARCH_MAX_FILE` | `256MB` | Larger files are found by name and columns only |
| `NEXORA_SEARCH_MAX_TEXT` | `1000000` | Characters kept per document |
| `NEXORA_SEARCH_WORKER` | `true` | Turn off on all but one server when several share a database |

## Known limits

- **Stemming is English only.** `policies` finds `policy`, but other languages match whole words. Diacritics are ignored
  in every language.
- **Not load-tested.** It has only run on small data sets. The 500 ms target on 10 million documents is unproven.
- **The bundled OpenSearch is a single node with its security plugin off.** The Docker network is its only protection.
  Use a secured cluster if other software shares that network.
- **Only the current version is searched.** Older versions and checked-out drafts are not.
- **Facets choose one value each.**
- **A deleted column's values stay searchable** on items that still hold them, until those items are edited and rebuilt.
- **OCR quality is unmeasured on real scans.** It was tested on clean, generated images.
