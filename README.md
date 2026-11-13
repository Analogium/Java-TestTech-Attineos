# Books – CRUD Spring Boot

API REST de gestion de livres (`title`, `author`, `publicationYear`).
Java 21 · Spring Boot 4.1 · Spring Data JPA · H2 en mémoire · Bean Validation.

Note: On n'a pas de service, on utilise directement le repository étant donné que la logique est simple et que c'est un simple CRUD sur une entité simple.

J'ai également intégré docker, même si il s'agit d'un projet simple, si vous n'avez pas envie d'avoir à installer tout le nécessaire c'est préférable, surtout pour un simple exercice d'une fois.

## Lancer

Avec Docker (seul prérequis) :

```bash
docker build -t books .
docker run --rm -p 8080:8080 books
```

Sans Docker (JDK 21 requis, Maven est téléchargé par le wrapper ; sous Windows : `mvnw.cmd`,
si `./mvnw` répond « Permission denied » : `chmod +x mvnw`) :

```bash
./mvnw spring-boot:run     # http://localhost:8080
./mvnw test                # tests d'intégration MockMvc
```

## Endpoints

| Méthode | URL           | Réponse                      |
|---------|---------------|------------------------------|
| GET     | /books        | 200 + page de livres         |
| GET     | /books/{id}   | 200, ou 404                  |
| POST    | /books        | 201 + livre créé, ou 400     |
| PUT     | /books/{id}   | 200 + livre modifié, 400/404/409 |
| DELETE  | /books/{id}   | 204, ou 404                  |

Règles de validation (sinon 400, avec la liste des champs fautifs dans `errors`) :
- `title`, `author` : obligatoires, espaces de début/fin retirés, 255 caractères max, sans caractère de contrôle ni `<` `>` (XSS).
- `publicationYear` : entier obligatoire, > 0 et pas dans le futur (`1965.9` est refusé, pas tronqué).
- `id` envoyé par le client : ignoré. Clé JSON en double : refusée. Corps > 10 Ko : refusé.
- `sort` : uniquement `id`, `title`, `author`, `publicationYear`.

Toutes les erreurs ont le même format (RFC 9457) : `{"title", "status", "detail", "instance"}`.
Mise à jour perdue : chaque livre a un champ `version` (incrémenté à chaque modification).
Un PUT doit renvoyer la `version` lue ; si quelqu'un a modifié le livre entre-temps → 409, il faut relire puis réessayer.
Un PUT/DELETE sur un livre supprimé au même moment par une autre requête répond aussi 409.

Toutes les réponses portent `X-Content-Type-Options: nosniff` et `Content-Security-Policy: default-src 'none'`.

```bash
curl -X POST localhost:8080/books -H 'Content-Type: application/json' \
  -d '{"title":"Dune","author":"Frank Herbert","publicationYear":1965}'
curl "localhost:8080/books?page=0&size=10&sort=title,asc"   # pagination (size max 100)
curl -X PUT localhost:8080/books/1 -H 'Content-Type: application/json' \
  -d '{"title":"Dune","author":"Frank Herbert","publicationYear":1966,"version":0}'
curl -X DELETE localhost:8080/books/1
```
