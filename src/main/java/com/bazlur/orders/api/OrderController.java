package com.bazlur.orders.api;

import module java.base;
import module java.sql;

import com.bazlur.orders.application.OrderException;
import com.bazlur.orders.application.OrderService;
import com.bazlur.orders.json.Json;
import com.bazlur.orders.persistence.ConventionalOrderRepository;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.bazlur.orders.application.OrderException.Kind.*;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final OrderDocumentRepository documents;
    private final ConventionalOrderRepository conventional;
    private final OrderService service;

    public OrderController(OrderDocumentRepository documents, ConventionalOrderRepository conventional, OrderService service) {
        this.documents = documents;
        this.conventional = conventional;
        this.service = service;
    }

    @GetMapping("/orders/{id:[1-9][0-9]*}")
    public ResponseEntity<String> getOrder(@PathVariable String id) throws SQLException {
        return json(documents.getOrder(parseId(id)).orElseThrow(() -> new OrderException(NOT_FOUND, "Order not found")));
    }

    @PutMapping(path = "/orders/{id:[1-9][0-9]*}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> changeStatus(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        var bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new OrderException(TOO_LARGE, "Document too large");
        return json(service.changeStatus(parseId(id), new String(bytes, StandardCharsets.UTF_8)));
    }

    @GetMapping("/orders/{id:[1-9][0-9]*}/relational")
    public ResponseEntity<String> getRelationalOrder(@PathVariable String id) throws IOException, SQLException {
        return json(Json.encode(conventional.getOrder(parseId(id)).orElseThrow(() -> new OrderException(NOT_FOUND, "Order not found"))));
    }

    @GetMapping("/customers/{id:[1-9][0-9]*}/orders")
    public ResponseEntity<String> getCustomerOrders(@PathVariable String id) throws SQLException {
        return json(documents.getCustomerOrders(parseId(id)).orElseThrow(() -> new OrderException(NOT_FOUND, "Customer not found")));
    }

    private static ResponseEntity<String> json(String document) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(document);
    }

    private static long parseId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException _) { throw new OrderException(INVALID_REQUEST, "Invalid ID"); }
    }
}
