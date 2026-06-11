package com.nousresearch.hermes.browser.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nousresearch.hermes.browser.BrowserBridgeConfig;

/**
 * Small CLI entrypoint for verifying an external BrowserBridge daemon.
 *
 * Usage:
 * java ... com.nousresearch.hermes.browser.contract.BrowserBridgeContractCli <endpoint> [provider]
 */
public final class BrowserBridgeContractCli {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BrowserBridgeContractCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: BrowserBridgeContractCli <endpoint> [provider]");
            System.exit(2);
        }
        String endpoint = args[0];
        String provider = args.length > 1 ? args[1] : "kimi";
        BrowserBridgeContractReport report = BrowserBridgeContractVerifier.verify(new BrowserBridgeConfig(provider, endpoint, 10000));
        System.out.println(MAPPER.writeValueAsString(report.toMap()));
        System.exit(report.ok() ? 0 : 1);
    }
}
