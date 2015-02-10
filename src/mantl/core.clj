(ns mantl.core
  (:import (org.antlr.v4.runtime ANTLRInputStream
                                 RuleContext
                                 CommonTokenStream
                                 ListTokenSource
                                 CommonToken
                                 ParserRuleContext
                                 TokenFactory
                                 Token)
           (org.antlr.v4.runtime.misc Pair)
           (org.antlr.v4.runtime.tree ParseTreeWalker
                                      RuleNode
                                      ErrorNode
                                      TerminalNode))
  (:require clojure.walk))

;This is an immutable equivalent of an ANTLR4 token; all it is missing is the
; InputStream (which by it's nature is mutable) and the
; TokenSource (which is often mutable). These immutable fields still (more-than)
; represent the useful information in a token.
(defrecord token
  [type text channel start-index stop-index line position-in-line token-index]
  Token
  (getChannel [this] (or channel Token/DEFAULT_CHANNEL))
  (getCharPositionInLine [this] (or position-in-line 0))
  (getInputStream [this] nil)
  (getLine [this] (or line 0))
  (getStartIndex [this] (or start-index -1))
  (getStopIndex [this] (or stop-index -1))
  (getText [this] text)
  (getTokenIndex [this] token-index)
  (getTokenSource [this] nil)
  (getType [this] type))


(def token-factory
  (reify TokenFactory
    (create [this source type text channel start stop line position-in-line]
      ;token-index -1 is the convention from CommonTokenFactory
      (->token type text channel start stop line position-in-line -1)) 
    (create [this type text]
      (map->token {:type type :text text}))))
      


;(defn- build-tree 
;  "Takes a ParseTree and recursively turns it into a Clojure data structure."
;  [e]
;  (cond
;    (keyword? e) e
;    (instance? ErrorNode e) (unwrap-error (.getSymbol e))
;    (instance? TerminalNode e) (unwrap-token (.getSymbol e))
;    (instance? RuleNode e) {:rule-name (rule-name e)
;                            :src-line (antlr-token-line (.getStop e))
;                            :arguments (map build-tree (.children e))}
;    :else (throw (Exception. (str "Error: unknown parseTree in build-tree at: " e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Text Mangling

(defn- unproper-name
  "Takes a string and converts the first letter to lower case."
  [s]
  {:pre [(string? s)]
   :post [(string? %) (or (not (Character/isLetter (first %)))
                          (Character/isLowerCase (first %)))]}
  (apply str
    (cons (Character/toLowerCase (first s)) (rest s))))

;TODO: Better name
; Use on (.getSimpleName)
(defn remove-context
  "Gets the name of a rule extracting it from the generated class
  name. This is used over the array tokenNames
  in the Parser because this does not contain labelled rules in the grammar
  (i.e. rules names with #...)."
    [x]
    {:pre [(string? x)
           (.endsWith x "Context")]}
    (let  [name-len (- (.length x) 7)]
    ;Make the name lower case
    (unproper-name (.substring x 0 name-len))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;Reflection magic
(defn invoke [m o & args]
  (.invoke m o (into-array args)))

; ANTLR4 adds:
;   getRuleIndex
;   ruleName()
;   ruleName(int i) [if more than one rule name]
(defn named-rules [c]
  {:pre [(instance? ParserRuleContext c)]}
  (let [named-meths
        (->> (class c)
             .getDeclaredMethods
             (filter #(zero? (.getParameterCount %)))
             (filter #(isa? (.getReturnType %) Object)))]
    (zipmap (map #(keyword (.getName %)) named-meths)
            (map #(invoke % c) named-meths))))

;TODO: Make sure named-rules don't clash with predefined ones!
(defn wrap-rule [r]
  (merge {:rule-name (remove-context (.getSimpleName (class r)))
          :src-line (.getStop r)
          :child-ren (.children r)}
         (named-rules r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- count-newlines [s]
  "Counts the number of occurances of newlines in a String"
  {:pre [(string? s)]}
  (count (filter #(= % \newline) s)))


(defn- lexerClassname [grammar]
  (symbol (str grammar "Lexer")))
(defn- parserClassname [grammar]
  (symbol (str grammar "Parser")))

(defmacro importLexer 
  ([grammar package]
   (if package
     `(import (~package ~(lexerClassname grammar)))
     `(import ~(lexerClassname grammar)))))

(defmacro importParser 
  ([grammar package]
   (if package
     `(import (~package ~(parserClassname grammar))))
     `(import ~(parserClassname grammar))))

(defmacro ANTLR-lexer
  "Generates a Lexer as from ANTLR. Break through the abstraction; prefer lexer."
  ([grammar package]
   (let [arg (gensym)]
     `(do
        (importLexer ~grammar ~package)
        (fn [~arg]
          (new ~(lexerClassname grammar) ~arg)))))
  ([grammar] `(ANTLR-lexer ~grammar nil)))

(defmacro lexer 
  ([grammar package]
   (let [arg (gensym)]
     `(fn [~arg]
        (-> ~arg
             ANTLRInputStream.
             ((ANTLR-lexer ~grammar ~package))
             (doto (.setTokenFactory token-factory))
             .getAllTokens
             ))))
  ([grammar] `(lexer ~grammar nil)))

(defmacro ANTLR-parser
  "Generates an ANTLR type parser. Break through abstraction layer; prefer parser."
  ([grammar package]
   (let [token-stream (gensym)]
     `(do
        (importParser ~grammar ~package)
        (fn [~token-stream]
          (new ~(parserClassname grammar) ~token-stream)))))
  ([grammar] `(ANTLR-parser ~grammar nil)))

(defmacro parser
  ;Inefficient for multiple partial parsers: Could share structure
  ([rule grammar package]
   (let [source (gensym)]
     `(fn [~source]
        (->
          (->> ~source
               ListTokenSource.
               CommonTokenStream.
               ((ANTLR-parser ~grammar ~package))
               )
          ;Don't print to std out
          (doto .removeErrorListeners)
          (. ~rule)
          ;Do something here to build the tree
            ;build-tree)))))
            ))))
  ([rule grammar] `(parser ~rule ~grammar nil)))


(defmacro lexer-parser
  ([rule grammar package]
   (let [arg (gensym)]
     `(fn [~arg]
        (->
          ~arg
          ANTLRInputStream.
          ((ANTLR-lexer ~grammar ~package))
          CommonTokenStream.
          ((ANTLR-parser ~grammar ~package))
          ;Don't print errors to STDOUT
          (doto .removeErrorListeners)
          (. ~rule)
            ;build-tree)))))
            ))))
  ([rule grammar] `(lexer-parser ~rule ~grammar nil)))


