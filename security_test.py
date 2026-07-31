#!/usr/bin/env python3
"""
SecureMessenger Security Testing Script
Penetration testing and security validation tools.

Usage:
    python3 security_test.py --target <app-package>
    python3 security_test.py --adb --static-analysis
"""

import argparse
import subprocess
import sys
import json
import re
from pathlib import Path

# Colors for output
class Colors:
    RED = '\033[91m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    END = '\033[0m'

def print_status(message, color=Colors.BLUE):
    print(f"{color}[✓]{Colors.END} {message}")

def print_error(message):
    print(f"{Colors.RED}[✗]{Colors.END} {message}")

def print_warning(message):
    print(f"{Colors.YELLOW}[!]{Colors.END} {message}")

def print_success(message):
    print(f"{Colors.GREEN}[✓]{Colors.END} {message}")

class SecurityTester:
    def __init__(self, target_package):
        self.target_package = target_package
        self.results = {
            "passed": [],
            "failed": [],
            "warnings": []
        }

    def run_adb_command(self, command):
        """Execute ADB command."""
        try:
            result = subprocess.run(
                f"adb {command}",
                shell=True,
                capture_output=True,
                text=True,
                timeout=30
            )
            return result.stdout, result.stderr
        except subprocess.TimeoutExpired:
            return "", "Command timed out"

    def check_exported_activities(self):
        """Check for exported activities that shouldn't be exported."""
        print_status("Checking exported activities...")

        stdout, _ = self.run_adb_command(
            f"shell dumpsys package {self.target_package}"
        )

        exported_activities = re.findall(
            r'ActivityFilter.*?(?=ActivityFilter|\Z)',
            stdout,
            re.DOTALL
        )

        dangerous_exports = []
        for activity in exported_activities:
            if 'exported=true' in activity.lower():
                activity_name = re.search(r'Activity\{[^}]*\s([^/\s}]+)', activity)
                if activity_name:
                    dangerous_exports.append(activity_name.group(1))

        if dangerous_exports:
            print_error(f"Found {len(dangerous_exports)} potentially dangerous exported activities:")
            for act in dangerous_exports:
                print(f"  - {act}")
            self.results["failed"].append({
                "test": "exported_activities",
                "details": dangerous_exports
            })
        else:
            print_success("No dangerous exported activities found")
            self.results["passed"].append("exported_activities")

    def check_debuggable(self):
        """Check if app is debuggable."""
        print_status("Checking debuggable flag...")

        stdout, _ = self.run_adb_command(
            f"shell dumpsys package {self.target_package} | grep debuggable"
        )

        if "debuggable=true" in stdout.lower():
            print_error("App is debuggable! This is a security risk.")
            self.results["failed"].append({
                "test": "debuggable",
                "details": "App is debuggable"
            })
        else:
            print_success("App is not debuggable")
            self.results["passed"].append("debuggable")

    def check_backup_allowed(self):
        """Check if app allows backup."""
        print_status("Checking backup configuration...")

        stdout, _ = self.run_adb_command(
            f"shell dumpsys package {self.target_package} | grep allowBackup"
        )

        if "allowBackup=true" in stdout.lower():
            print_warning("App allows backup. Consider disabling for sensitive data.")
            self.results["warnings"].append({
                "test": "backup_allowed",
                "details": "Backup is enabled"
            })
        else:
            print_success("App does not allow backup")
            self.results["passed"].append("backup_allowed")

    def check_cleartext_traffic(self):
        """Check if cleartext traffic is allowed."""
        print_status("Checking cleartext traffic configuration...")

        stdout, _ = self.run_adb_command(
            f"shell dumpsys package {self.target_package} | grep usesCleartextTraffic"
        )

        if "usesCleartextTraffic=true" in stdout.lower():
            print_error("App allows cleartext traffic! This is a security risk.")
            self.results["failed"].append({
                "test": "cleartext_traffic",
                "details": "Cleartext traffic is allowed"
            })
        else:
            print_success("App does not allow cleartext traffic")
            self.results["passed"].append("cleartext_traffic")

    def check_shared_user_id(self):
        """Check for sharedUserId configuration."""
        print_status("Checking sharedUserId...")

        stdout, _ = self.run_adb_command(
            f"shell dumpsys package {self.target_package} | grep sharedUserId"
        )

        if "sharedUserId" in stdout and "null" not in stdout:
            print_warning("App uses sharedUserId. This may allow other apps to access data.")
            self.results["warnings"].append({
                "test": "shared_user_id",
                "details": "sharedUserId is configured"
            })
        else:
            print_success("No sharedUserId configured")
            self.results["passed"].append("shared_user_id")

    def check_database_encryption(self):
        """Check if app databases are encrypted."""
        print_status("Checking database encryption...")

        # This is a basic check - real verification would require deeper analysis
        stdout, _ = self.run_adb_command(
            f"shell ls /data/data/{self.target_package}/databases/"
        )

        if stdout.strip():
            print_warning("App has databases. Verify they are encrypted with SQLCipher.")
            self.results["warnings"].append({
                "test": "database_encryption",
                "details": "Databases found - manual verification needed"
            })
        else:
            print_success("No databases found or cannot access")
            self.results["passed"].append("database_access")

    def check_file_permissions(self):
        """Check file permissions for app data directory."""
        print_status("Checking file permissions...")

        stdout, _ = self.run_adb_command(
            f"shell ls -la /data/data/{self.target_package}/"
        )

        if "rw-rw-rw" in stdout or "rwxrwxrwx" in stdout:
            print_error("Found world-readable/writable files!")
            self.results["failed"].append({
                "test": "file_permissions",
                "details": "World-readable/writable files found"
            })
        else:
            print_success("File permissions appear correct")
            self.results["passed"].append("file_permissions")

    def run_static_analysis(self, apk_path):
        """Run static analysis on APK file."""
        print_status("Running static analysis...")

        if not Path(apk_path).exists():
            print_error(f"APK file not found: {apk_path}")
            return

        # Check for hardcoded secrets
        print_status("Checking for hardcoded secrets...")
        result = subprocess.run(
            f"strings {apk_path} | grep -iE '(password|secret|key|token|api_key)' | head -20",
            shell=True,
            capture_output=True,
            text=True
        )

        if result.stdout.strip():
            print_warning("Potential hardcoded secrets found:")
            print(result.stdout)
            self.results["warnings"].append({
                "test": "hardcoded_secrets",
                "details": "Potential secrets found in binary"
            })
        else:
            print_success("No obvious hardcoded secrets found")
            self.results["passed"].append("no_hardcoded_secrets")

        # Check for debug logging
        print_status("Checking for debug logging...")
        result = subprocess.run(
            f"strings {apk_path} | grep -iE '(Log\.d|Log\.v|System\.out|println)' | head -10",
            shell=True,
            capture_output=True,
            text=True
        )

        if result.stdout.strip():
            print_warning("Potential debug logging found")
            self.results["warnings"].append({
                "test": "debug_logging",
                "details": "Debug logging statements found"
            })
        else:
            print_success("No debug logging found")
            self.results["passed"].append("no_debug_logging")

    def generate_report(self):
        """Generate security test report."""
        print("\n" + "="*50)
        print("SECURITY TEST REPORT")
        print("="*50)

        print(f"\n{Colors.GREEN}Passed: {len(self.results['passed'])}{Colors.END}")
        for test in self.results["passed"]:
            print(f"  ✓ {test}")

        if self.results["failed"]:
            print(f"\n{Colors.RED}Failed: {len(self.results['failed'])}{Colors.END}")
            for test in self.results["failed"]:
                print(f"  ✗ {test['test']}: {test['details']}")

        if self.results["warnings"]:
            print(f"\n{Colors.YELLOW}Warnings: {len(self.results['warnings'])}{Colors.END}")
            for test in self.results["warnings"]:
                print(f"  ! {test['test']}: {test['details']}")

        print("\n" + "="*50)

        # Save JSON report
        report_path = Path("security_report.json")
        with open(report_path, "w") as f:
            json.dump(self.results, f, indent=2)

        print(f"Report saved to: {report_path}")

def main():
    parser = argparse.ArgumentParser(description="SecureMessenger Security Testing Tool")
    parser.add_argument("--target", required=True, help="Target package name")
    parser.add_argument("--adb", action="store_true", help="Run ADB-based tests")
    parser.add_argument("--static-analysis", help="Path to APK for static analysis")

    args = parser.parse_args()

    tester = SecurityTester(args.target)

    if args.adb:
        print("Running ADB-based security tests...")
        tester.check_exported_activities()
        tester.check_debuggable()
        tester.check_backup_allowed()
        tester.check_cleartext_traffic()
        tester.check_shared_user_id()
        tester.check_database_encryption()
        tester.check_file_permissions()

    if args.static_analysis:
        print("Running static analysis...")
        tester.run_static_analysis(args.static_analysis)

    tester.generate_report()

    # Exit with error code if any tests failed
    if tester.results["failed"]:
        sys.exit(1)
    sys.exit(0)

if __name__ == "__main__":
    main()
