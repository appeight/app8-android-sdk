import Foundation
public final class A8Log: @unchecked Sendable {
  func error(_ s: @autoclosure () -> String) {}
  func warning(_ s: @autoclosure () -> String) {}
  func debug(_ s: @autoclosure () -> String) {}
}
final class App8Context { let logger = A8Log() }
