package com.example.books;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Year;
import java.util.List;

@RestController
@RequestMapping("/books")
public class BookController {

    // Request body is a separate record so clients can't set the id (mass assignment).
    // @Size matches the VARCHAR(255) column: without it a long title is a 500 instead of a 400.
    // [^\p{Cc}<>]* = no control characters (NUL, line break, tab...) that would break logs, CSV exports or UIs,
    // and no < > so no HTML tag (<script>, <img onerror=...>) can be stored (stored XSS).
    // ' " & stay allowed ("Harry's", "Pride & Prejudice"): the front must still escape what it displays.
    // version: the one the client last read, required by PUT (lost update), ignored by POST like the id.
    public record BookRequest(
            @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cc}<>]*", message = "must not contain control characters, < or >")
            String title,
            @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cc}<>]*", message = "must not contain control characters, < or >")
            String author,
            @NotNull @Positive Integer publicationYear,
            Long version) {

        // Runs when Jackson builds the record, so before validation: " Dune " is stored (and size-checked) as "Dune".
        public BookRequest {
            title = trim(title);
            author = trim(author);
        }

        @AssertTrue(message = "must not be in the future")
        public boolean isPublicationYearNotInFuture() {
            return publicationYear == null || publicationYear <= Year.now().getValue();
        }

        // \s = ASCII spaces/tabs/line breaks, \p{Z} = every Unicode space, including the no-break
        // space that String.strip() keeps: a title made only of spaces becomes "" and @NotBlank rejects it.
        private static String trim(String s) {
            return s == null ? null : s.replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$", "");
        }
    }

    // What the API returns, decoupled from the JPA entity: a field added to Book later (internal flag,
    // relation...) is not exposed by accident, and renaming a column doesn't change the JSON contract.
    public record BookResponse(Long id, String title, String author, Integer publicationYear, Long version) {
        static BookResponse from(Book book) {
            return new BookResponse(book.getId(), book.getTitle(), book.getAuthor(), book.getPublicationYear(), book.getVersion());
        }
    }

    // Whitelist of what ?sort= may contain: anything else never reaches the SQL ORDER BY.
    private static final List<String> SORTABLE = List.of("id", "title", "author", "publicationYear");

    private final BookRepository repository;

    public BookController(BookRepository repository) {
        this.repository = repository;
    }

    // ?page=0&size=20&sort=title,asc  (size is capped at 100 in application.properties)
    @GetMapping
    public PagedModel<BookResponse> findAll(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw badRequest("Cannot sort by '" + order.getProperty() + "', allowed: " + SORTABLE);
            }
        }
        // JPA takes the offset (page * size) as an int: ?page=99999999 would otherwise be a 500.
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            throw badRequest("page is too large");
        }
        // Always end with id: two books with the same title keep the same order on every page,
        // so none is shown twice or skipped while paginating.
        Sort sort = pageable.getSort().getOrderFor("id") == null
                ? pageable.getSort().and(Sort.by("id"))
                : pageable.getSort();
        return new PagedModel<>(repository.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort))
                .map(BookResponse::from));
    }

    @GetMapping("/{id}")
    public BookResponse findById(@PathVariable Long id) {
        return BookResponse.from(findBook(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookResponse create(@Valid @RequestBody BookRequest req) {
        return BookResponse.from(repository.save(new Book(req.title(), req.author(), req.publicationYear())));
    }

    @PutMapping("/{id}")
    @Transactional
    public BookResponse update(@PathVariable Long id, @Valid @RequestBody BookRequest req) {
        if (req.version() == null) {
            throw badRequest("version is required: send the version of the book you read");
        }
        Book book = findBook(id);
        // Lost update: Alice and Bob both read version 3, Bob saves (-> version 4),
        // Alice's PUT still says 3, so she gets a 409 instead of silently erasing Bob's change.
        if (!req.version().equals(book.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Book was modified by someone else (current version " + book.getVersion() + "): reload it and retry");
        }
        book.update(req.title(), req.author(), req.publicationYear());
        // Flush now runs "UPDATE ... WHERE id = ? AND version = ?" and increments version, so the response
        // carries the new version. If another PUT committed between our check and now, 0 rows match
        // -> OptimisticLockingFailureException -> 409 below.
        return BookResponse.from(repository.saveAndFlush(book));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        repository.delete(findBook(id));
    }

    private Book findBook(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book " + id + " not found"));
    }

    // Spring's 400 only says "Invalid request content": add which fields are wrong and why.
    @ExceptionHandler
    public ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        ProblemDetail problem = e.getBody();
        problem.setProperty("errors", e.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList());
        return problem;
    }

    // Another request changed or deleted the book between our read and our write (PUT or DELETE): 409, not 500.
    @ExceptionHandler
    public ProblemDetail concurrentChange(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Book was modified or deleted by another request, retry");
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
