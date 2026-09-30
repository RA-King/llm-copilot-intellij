package com.llmcopilot.index;

import com.llmcopilot.index.SourceSummary.FileSummary;
import com.llmcopilot.index.SourceSummary.Kind;
import com.llmcopilot.index.SourceSummary.Symbol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for reducing a source file to its declarations and imports. */
class SourceSummaryTest {

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    private static FileSummary summarise(String path, String language, String... lines) {
        String text = lines(lines);
        return SourceSummary.summarise(path, text, language, 1L, text.length());
    }

    private static List<String> names(FileSummary file) {
        return file.symbols().stream().map(Symbol::name).toList();
    }

    private static Optional<Symbol> named(FileSummary file, String name) {
        return file.symbols().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    // ── Java ────────────────────────────────────────────────────────────────

    @Test void readsJava() {
        FileSummary file = summarise("src/OrderService.java", "java",
            "package com.shop;",
            "",
            "import com.shop.model.Order;",
            "import java.util.List;",
            "",
            "public class OrderService {",
            "    public Order find(String id) {",
            "        return null;",
            "    }",
            "}");

        assertTrue(names(file).containsAll(List.of("OrderService", "find")));
        assertEquals(List.of("com.shop.model.Order", "java.util.List"), file.imports());
        assertEquals(List.of("Order", "List"), file.importedNames());
    }

    @Test void recordsWhereEachDeclarationIs() {
        FileSummary file = summarise("src/OrderService.java", "java",
            "package com.shop;",
            "",
            "public class OrderService {",
            "}");

        Symbol service = named(file, "OrderService").orElseThrow();
        assertEquals(2, service.line());
        assertEquals("public class OrderService {", service.signature());
        assertEquals(Kind.CLASS, service.kind());
        assertTrue(service.exported());
    }

    // ── TypeScript ──────────────────────────────────────────────────────────

    @Test void readsTypeScript() {
        FileSummary file = summarise("src/orders.ts", "typescript",
            "import { Order, OrderLine } from './models';",
            "import * as fs from 'fs';",
            "const { readFile } = require('node:fs/promises');",
            "",
            "export interface Repository {}",
            "export class OrderRepository implements Repository {}",
            "export const total = (lines: OrderLine[]) => lines.length;",
            "export type Money = number;",
            "export enum Currency { GBP, USD }",
            "export function format(m: Money): string { return String(m); }");

        List<String> kinds = file.symbols().stream()
            .map(s -> s.kind() + ":" + s.name()).toList();

        assertTrue(kinds.containsAll(List.of(
            "INTERFACE:Repository", "CLASS:OrderRepository", "FUNCTION:total",
            "TYPE:Money", "ENUM:Currency", "FUNCTION:format")));

        assertTrue(file.imports().containsAll(List.of("./models", "fs", "node:fs/promises")));
        assertTrue(file.importedNames().containsAll(List.of("Order", "OrderLine", "readFile")));
    }

    @Test void doesNotReadControlFlowAsADeclaration() {
        FileSummary file = summarise("a.ts", "typescript",
            "if (x) {", "  for (const y of z) {}", "}");
        assertTrue(file.symbols().isEmpty(), "got " + names(file));
    }

    // ── Other languages ─────────────────────────────────────────────────────

    @Test void readsPython() {
        FileSummary file = summarise("app/service.py", "python",
            "from app.models import Order, Line",
            "import json",
            "",
            "MAX_LINES = 100",
            "",
            "class OrderService:",
            "    def total(self, order):",
            "        return 0",
            "",
            "async def load(path):",
            "    return None");

        assertTrue(names(file).containsAll(List.of("MAX_LINES", "OrderService", "total", "load")));
        assertEquals(List.of("app.models", "json"), file.imports());
        assertTrue(file.importedNames().containsAll(List.of("Order", "Line")));
    }

    @Test void readsRust() {
        FileSummary file = summarise("src/repo.rs", "rust",
            "use crate::model::{Order, Line};",
            "use std::fmt;",
            "",
            "pub struct Repo;",
            "pub trait Find { fn find(&self) -> Option<Order>; }",
            "pub async fn load() -> Repo { Repo }");

        List<String> kinds = file.symbols().stream()
            .map(s -> s.kind() + ":" + s.name()).toList();
        assertTrue(kinds.containsAll(List.of("STRUCT:Repo", "TRAIT:Find", "FUNCTION:load")));
        assertTrue(file.importedNames().containsAll(List.of("Order", "Line")));
    }

    @Test void readsGo() {
        FileSummary file = summarise("shop/order.go", "go",
            "package shop",
            "",
            "type Order struct {",
            "    ID string",
            "}",
            "",
            "func (o *Order) Total() int { return 0 }",
            "func Load(id string) *Order { return nil }");

        assertTrue(names(file).containsAll(List.of("Order", "Total", "Load")));
    }

    @Test void readsKotlin() {
        FileSummary file = summarise("src/Repo.kt", "kotlin",
            "import com.shop.Order",
            "",
            "data class Repo(val id: String)",
            "suspend fun load(id: String): Order? = null");

        assertTrue(names(file).containsAll(List.of("Repo", "load")));
        assertEquals(List.of("Order"), file.importedNames());
    }

    @Test void returnsSomethingUsableForALanguageItHasNoPatternsFor() {
        FileSummary file = summarise("a.erl", "erlang", "whatever.");
        assertTrue(file.symbols().isEmpty());
        assertEquals("erlang", file.language());
    }

    // ── Limits ──────────────────────────────────────────────────────────────

    @Test void stopsLookingForImportsPastTheHeadOfTheFile() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) body.append("int x = 1;\n");
        body.append("import com.shop.Late;");

        FileSummary file = SourceSummary.summarise(
            "A.java", body.toString(), "java", 1L, body.length());
        assertTrue(file.imports().isEmpty());
    }

    @Test void skipsAMinifiedLineRatherThanTryingToParseIt() {
        String long_ = "export const a = " + "1+".repeat(300) + "1;";
        FileSummary file = SourceSummary.summarise("a.ts", long_, "typescript", 1L, long_.length());
        assertTrue(file.symbols().isEmpty());
    }
}
