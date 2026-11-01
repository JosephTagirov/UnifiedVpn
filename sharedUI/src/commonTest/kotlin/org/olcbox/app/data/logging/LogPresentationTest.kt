package org.olcbox.app.data.logging
import kotlin.test.*
class LogPresentationTest {
 @Test fun preservesTheEngineLevelEvenWhenMessageContainsFailureWords() {
  assertEquals("WARN",LogPresentation.severity("VK Calls: [ice] WARN: Failed to ping without candidate pairs"))
  assertEquals("ERROR",LogPresentation.severity("sing-box: ERROR[0000] handshake failed"))
  assertEquals("INFO",LogPresentation.severity("INFO[0001] previous error cleared"))
 }
 @AfterTest fun reset() { LogPresentation.select(LogSelection()) }
 @Test fun copiesOnlyTheLastRequestedRowsInOriginalOrder() {
  val logs=(1..1000).map { "line $it" }
  LogPresentation.select(LogSelection(lastLines=50))
  val shown=LogPresentation.lines(logs);assertEquals(50,shown.size);assertEquals("line 951",shown.first());assertEquals("line 1000",shown.last())
  val text=LogPresentation.export(logs);assertFalse(text.lineSequence().any{it=="line 950"});assertTrue(text.contains("Entries: 50 / 1000"))
 }
 @Test fun allAndEmptyLogsAreHandledWithoutNegativeLimits() {
  assertEquals(listOf("a","b"),LogPresentation.lines(listOf("a","b"),LogSelection(lastLines=0)))
  assertEquals(emptyList(),LogPresentation.lines(emptyList()))
 }
 @Test fun problemFilterUsesSeverityAndThenTakesTheLastMatches() {
  val logs=listOf("INFO startup","ERROR first","WARN retry","INFO done","fatal last")
  assertEquals(listOf("WARN retry","fatal last"),LogPresentation.lines(logs,LogSelection(lastLines=2,problemsOnly=true)))
 }
 @Test fun readableEntriesHaveTimeLevelAndProtocolWithoutDuplicatePrefix() {
  val line=LogPresentation.entry("VLESS connection failed","12:34:56.789")
  assertEquals("[12:34:56.789] [ERROR] [VLESS] VLESS connection failed",line)
  assertEquals(line,LogPresentation.entry(line,"13:00:00.000"))
  assertTrue(LogPresentation.entry("OpenFlux ready","12:00:00.000").contains("[INFO] [OpenFlux]"))
 }
}
