FROM postgres:17.11@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3
COPY --chmod=755 infra/postgres/start.sh /usr/local/bin/tapflow-postgres
USER postgres
ENTRYPOINT ["/usr/local/bin/tapflow-postgres"]
CMD ["postgres", "-c", "ssl=on", "-c", "ssl_cert_file=/tmp/tapflow-tls/server.crt", "-c", "ssl_key_file=/tmp/tapflow-tls/server.key"]
