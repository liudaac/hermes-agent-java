package com.nousresearch.hermes.tools.impl.web;

import java.util.List;
import java.util.Map;

/**
 * Interface for web search backends.
 */
public interface WebSearchBackend {
    
    /**
     * Search the web.
     */
    SearchResult search(String query, int count, Map<String, Object> options) throws Exception;
    
    /**
     * Extract content from a URL.
     */
    ExtractResult extract(String url, Map<String, Object> options) throws Exception;
    
    /**
     * Check if this backend is configured and available.
     */
    boolean isAvailable();
    
    /**
     * Get backend name.
     */
    String getName();
    
    /**
     * Search result.
     */
    class SearchResult {
        public final String query;
        public final List<SearchItem> results;
        public final String backend;
        public final long responseTimeMs;
        
        public SearchResult(String query, List<SearchItem> results, String backend, long responseTimeMs) {
            this.query = query;
            this.results = results;
            this.backend = backend;
            this.responseTimeMs = responseTimeMs;
        }
    }
    
    /**
     * Search item.
     */
    class SearchItem {
        public final String title;
        public final String url;
        public final String snippet;
        public final String content;
        public final String publishedDate;
        
        public SearchItem(String title, String url, String snippet, String content, String publishedDate) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
            this.content = content;
            this.publishedDate = publishedDate;
        }
    }
    
    /**
     * Extract result.
     */
    class ExtractResult {
        public final String url;
        public final String title;
        public final String content;
        public final String markdown;
        public final String backend;
        
        public ExtractResult(String url, String title, String content, String markdown, String backend) {
            this.url = url;
            this.title = title;
            this.content = content;
            this.markdown = markdown;
            this.backend = backend;
        }
    }
}
