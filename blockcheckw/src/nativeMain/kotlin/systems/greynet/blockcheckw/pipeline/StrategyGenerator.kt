package systems.greynet.blockcheckw.pipeline

// Порт логики генерации стратегий из blockcheck2.d/standard/
// Режим: SCANLEVEL=force (без оптимизаций, генерируем ВСЕ комбинации)

// --- Параметры из def.inc ---

private val FOOLINGS_TCP = listOf(
    "tcp_md5", "badsum", "tcp_seq=-3000", "tcp_seq=1000000",
    "tcp_ack=-66000:tcp_ts_up", "tcp_ts=-1000",
    "tcp_flags_unset=ACK", "tcp_flags_set=SYN",
)

private val TTL_RANGE = 1..12
private val AUTOTTL_RANGE = 1..5

private val HTTP_SPLITS = listOf("method+2", "midsld", "method+2,midsld")
private val TLS_SPLITS = listOf(
    "2", "1", "sniext+1", "sniext+4", "host+1", "midsld",
    "1,midsld", "1,midsld,1220",
    "1,sniext+1,host+1,midsld-2,midsld,midsld+2,endhost-1",
)
private val SPLIT_METHODS = listOf("multisplit", "multidisorder")

private val OOB_URPS = listOf("b", "0", "2", "midsld")

private val FAKE_BLOBS_HTTP = listOf("fake_default_http", "0x00000000")
private val FAKE_BLOBS_TLS = listOf("fake_default_tls", "0x00000000")

private val SEQOVL_SPLITS_HTTP = listOf("method+2", "method+2,midsld")
private val SEQOVL_DISORDER_HTTP = listOf(
    "method+1" to "method+2",
    "midsld-1" to "midsld",
    "method+1" to "method+2,midsld",
)

private val SEQOVL_SPLITS_TLS = listOf("10", "10,sniext+1", "10,sniext+4", "10,midsld")
private val SEQOVL_DISORDER_TLS = listOf(
    "1" to "2",
    "sniext" to "sniext+1",
    "sniext+3" to "sniext+4",
    "midsld-1" to "midsld",
    "1" to "2,midsld",
)

private val HOSTFAKE_VARIANTS = listOf("", "nofake1:", "nofake2:", "midhost=midsld:", "nofake1:midhost=midsld:", "nofake2:midhost=midsld:")
private val HOSTFAKE_DISORDERS = listOf("", "disorder_after:")

private val SYNDATA_SPLITS = listOf("", "multisplit", "multidisorder")

private val MISC_REPEATS = listOf(1, 20, 100, 260)
private val MISC_POSITIONS_HTTP = listOf("0,method+2", "0,midsld")
private val MISC_POSITIONS_TLS = listOf("0,1", "0,midsld")

// --- Генераторы стратегий ---

/** 10-http-basic.sh */
private fun httpBasic(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    for (s in listOf("http_hostcase", "http_hostcase:spell=hoSt", "http_domcase", "http_methodeol", "http_unixeol")) {
        add(listOf(payload, "--lua-desync=$s"))
    }
}

/** 15-misc.sh — tcpseg с repeats */
private fun miscHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    for (repeats in MISC_REPEATS) {
        for (pos in MISC_POSITIONS_HTTP) {
            add(listOf(payload, "--lua-desync=tcpseg:pos=$pos:ip_id=rnd:repeats=$repeats"))
        }
    }
}

private fun miscTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    for (repeats in MISC_REPEATS) {
        for (pos in MISC_POSITIONS_TLS) {
            add(listOf(payload, "--lua-desync=tcpseg:pos=$pos:ip_id=rnd:repeats=$repeats"))
        }
    }
}

/** 17-oob.sh — OOB с urp вариантами */
private fun oob(): List<List<String>> = buildList {
    for (urp in OOB_URPS) {
        add(listOf("--in-range=-s1", "--lua-desync=oob:urp=$urp"))
    }
}

/** 20-multi.sh — multisplit/multidisorder по позициям */
private fun multiHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    for (splitf in SPLIT_METHODS) {
        for (pos in HTTP_SPLITS) {
            add(listOf(payload, "--lua-desync=$splitf:pos=$pos"))
        }
    }
}

private fun multiTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    for (splitf in SPLIT_METHODS) {
        for (pos in TLS_SPLITS) {
            add(listOf(payload, "--lua-desync=$splitf:pos=$pos"))
        }
    }
}

private fun multiTls12(): List<List<String>> = buildList {
    addAll(multiTls())
    // wssize варианты
    val payload = "--payload=tls_client_hello"
    for (splitf in SPLIT_METHODS) {
        for (pos in TLS_SPLITS) {
            add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$pos"))
        }
    }
}

/** 23-seqovl.sh — TCP sequence overlapping */
private fun seqovlHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"

    // tcpseg seqovl
    add(listOf(payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=1", "--lua-desync=drop"))
    add(listOf(payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=#fake_default_http:seqovl_pattern=fake_default_http", "--lua-desync=drop"))

    // multisplit seqovl
    for (split in SEQOVL_SPLITS_HTTP) {
        add(listOf(payload, "--lua-desync=multisplit:pos=$split:seqovl=1"))
        add(listOf(payload, "--lua-desync=multisplit:pos=$split:seqovl=#fake_default_http:seqovl_pattern=fake_default_http"))
    }

    // multidisorder seqovl
    for ((f, f2) in SEQOVL_DISORDER_HTTP) {
        add(listOf(payload, "--lua-desync=multidisorder:pos=$f2:seqovl=$f"))
        add(listOf(payload, "--lua-desync=multidisorder:pos=$f2:seqovl=$f:seqovl_pattern=fake_default_http"))
    }
}

private fun seqovlTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"

    // tcpseg seqovl
    add(listOf(payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=1", "--lua-desync=drop"))
    add(listOf(payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=#fake_default_tls:seqovl_pattern=fake_default_tls", "--lua-desync=drop"))
    add(listOf(payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=#patmod:seqovl_pattern=patmod", "--lua-desync=drop"))

    // multisplit seqovl
    for (split in SEQOVL_SPLITS_TLS) {
        add(listOf(payload, "--lua-desync=multisplit:pos=$split:seqovl=1"))
        add(listOf(payload, "--lua-desync=multisplit:pos=$split:seqovl=#fake_default_tls:seqovl_pattern=fake_default_tls"))
        add(listOf(payload, "--lua-desync=multisplit:pos=$split:seqovl=#patmod:seqovl_pattern=patmod"))
    }

    // multidisorder seqovl
    for ((f, f2) in SEQOVL_DISORDER_TLS) {
        add(listOf(payload, "--lua-desync=multidisorder:pos=$f2:seqovl=$f"))
        add(listOf(payload, "--lua-desync=multidisorder:pos=$f2:seqovl=$f:seqovl_pattern=fake_default_tls"))
    }
}

private fun seqovlTls12(): List<List<String>> = buildList {
    addAll(seqovlTls())
    // wssize варианты
    val payload = "--payload=tls_client_hello"
    add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=1", "--lua-desync=drop"))
    add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=#fake_default_tls:seqovl_pattern=fake_default_tls", "--lua-desync=drop"))
    add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=tcpseg:pos=0,-1:seqovl=#patmod:seqovl_pattern=patmod", "--lua-desync=drop"))
    for (split in SEQOVL_SPLITS_TLS) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=multisplit:pos=$split:seqovl=1"))
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=multisplit:pos=$split:seqovl=#fake_default_tls:seqovl_pattern=fake_default_tls"))
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=multisplit:pos=$split:seqovl=#patmod:seqovl_pattern=patmod"))
    }
    for ((f, f2) in SEQOVL_DISORDER_TLS) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=multidisorder:pos=$f2:seqovl=$f"))
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=multidisorder:pos=$f2:seqovl=$f:seqovl_pattern=fake_default_tls"))
    }
}

/** 24-syndata.sh — SYN с данными */
private fun syndataHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    for (split in SYNDATA_SPLITS) {
        add(listOf("--lua-desync=syndata") + if (split.isNotEmpty()) listOf(payload, "--lua-desync=$split") else emptyList())
        add(listOf("--lua-desync=syndata:blob=fake_default_http") + if (split.isNotEmpty()) listOf(payload, "--lua-desync=$split") else emptyList())
    }
}

private fun syndataTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    for (split in SYNDATA_SPLITS) {
        val splitArgs = if (split.isNotEmpty()) listOf(payload, "--lua-desync=$split") else emptyList()
        add(listOf("--lua-desync=syndata") + splitArgs)
        add(listOf("--lua-desync=syndata:blob=0x1603") + splitArgs)
        add(listOf("--lua-desync=syndata:blob=fake_default_tls:tls_mod=rnd,dupsid,rndsni") + splitArgs)
        add(listOf("--lua-desync=syndata:blob=fake_default_tls:tls_mod=rnd,dupsid,sni=google.com") + splitArgs)
    }
}

private fun syndataTls12(): List<List<String>> = buildList {
    addAll(syndataTls())
    // wssize варианты
    val payload = "--payload=tls_client_hello"
    for (split in SYNDATA_SPLITS) {
        val splitArgs = if (split.isNotEmpty()) listOf(payload, "--lua-desync=$split") else emptyList()
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", "--lua-desync=syndata") + splitArgs)
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", "--lua-desync=syndata:blob=0x1603") + splitArgs)
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", "--lua-desync=syndata:blob=fake_default_tls:tls_mod=rnd,dupsid,rndsni") + splitArgs)
        add(listOf("--lua-desync=wssize:wsize=1:scale=6", "--lua-desync=syndata:blob=fake_default_tls:tls_mod=rnd,dupsid,sni=google.com") + splitArgs)
    }
}

/** 25-fake.sh — fake с TTL brute-force + foolings + autottl */
private fun fakeHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    val fake = "fake_default_http"

    // TTL brute-force
    for (ttl in TTL_RANGE) {
        for (ff in FAKE_BLOBS_HTTP) {
            add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_ttl=$ttl:repeats=1"))
            // с pktmod:ip_ttl=1 лимитером
            add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_ttl=$ttl:repeats=1",
                "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }

    // foolings
    for (fooling in FOOLINGS_TCP) {
        for (ff in FAKE_BLOBS_HTTP) {
            add(listOf(payload, "--lua-desync=fake:blob=$ff:$fooling:repeats=1"))
            // SYN с MD5
            if (fooling.contains("tcp_md5")) {
                add(listOf(payload, "--lua-desync=fake:blob=$ff:$fooling:repeats=1",
                    "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            }
        }
    }

    // autottl
    for (ttl in AUTOTTL_RANGE) {
        for (ff in FAKE_BLOBS_HTTP) {
            add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_autottl=-$ttl,3-20:repeats=1"))
            add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_autottl=-$ttl,3-20:repeats=1",
                "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }
}

/** fake для TLS — с вариациями (5 подстратегий из pktws_fake_https_vary) */
private fun fakeHttpsVary(fooling: String, payload: String, fake: String): List<List<String>> = buildList {
    add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1"))
    add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1"))
    add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1",
        "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid:repeats=1"))
    add(listOf(payload, "--lua-desync=multisplit:blob=$fake:$fooling:pos=2:nodrop:repeats=1"))
    add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid,padencap:repeats=1"))
    // SYN с MD5
    if (fooling.contains("tcp_md5")) {
        add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1",
            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
        add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1",
            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
        add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1",
            "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid:repeats=1",
            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
        add(listOf(payload, "--lua-desync=multisplit:blob=$fake:$fooling:pos=2:nodrop:repeats=1",
            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
        add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid,padencap:repeats=1",
            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
    }
}

private fun fakeTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    val fake = "fake_default_tls"

    // TTL brute-force
    for (ttl in TTL_RANGE) {
        addAll(fakeHttpsVary("ip_ttl=$ttl", payload, fake))
        // с pktmod лимитером
        for (s in fakeHttpsVary("ip_ttl=$ttl", payload, fake)) {
            add(s + listOf("--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }

    // foolings
    for (fooling in FOOLINGS_TCP) {
        addAll(fakeHttpsVary(fooling, payload, fake))
    }

    // autottl
    for (ttl in AUTOTTL_RANGE) {
        addAll(fakeHttpsVary("ip_autottl=-$ttl,3-20", payload, fake))
        for (s in fakeHttpsVary("ip_autottl=-$ttl,3-20", payload, fake)) {
            add(s + listOf("--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }
}

private fun fakeTls12(): List<List<String>> = buildList {
    addAll(fakeTls())
    // wssize варианты
    for (s in fakeTls()) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6") + s)
    }
}

/** 30-faked.sh — fakedsplit/fakeddisorder */
private fun fakedHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    val splitfs = listOf("fakedsplit", "fakeddisorder")

    for (splitf in splitfs) {
        // TTL
        for (ttl in TTL_RANGE) {
            for (split in HTTP_SPLITS) {
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1"))
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
        // foolings
        for (fooling in FOOLINGS_TCP) {
            for (split in HTTP_SPLITS) {
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:$fooling"))
                if (fooling.contains("tcp_md5")) {
                    add(listOf(payload, "--lua-desync=$splitf:pos=$split:$fooling:repeats=1",
                        "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                }
            }
        }
        // autottl
        for (ttl in AUTOTTL_RANGE) {
            for (split in HTTP_SPLITS) {
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1"))
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
}

private fun fakedTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    val splitfs = listOf("fakedsplit", "fakeddisorder")

    for (splitf in splitfs) {
        // TTL
        for (ttl in TTL_RANGE) {
            for (split in TLS_SPLITS) {
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1"))
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
        // foolings
        for (fooling in FOOLINGS_TCP) {
            for (split in TLS_SPLITS) {
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:$fooling"))
                if (fooling.contains("tcp_md5")) {
                    add(listOf(payload, "--lua-desync=$splitf:pos=$split:$fooling:repeats=1",
                        "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                }
            }
        }
        // autottl
        for (ttl in AUTOTTL_RANGE) {
            for (split in TLS_SPLITS) {
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1"))
                add(listOf(payload, "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
}

private fun fakedTls12(): List<List<String>> = buildList {
    addAll(fakedTls())
    // wssize варианты
    val payload = "--payload=tls_client_hello"
    val splitfs = listOf("fakedsplit", "fakeddisorder")
    for (splitf in splitfs) {
        for (ttl in TTL_RANGE) {
            for (split in TLS_SPLITS) {
                add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1"))
                add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
        for (fooling in FOOLINGS_TCP) {
            for (split in TLS_SPLITS) {
                add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$split:$fooling"))
                if (fooling.contains("tcp_md5")) {
                    add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$split:$fooling:repeats=1",
                        "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                }
            }
        }
        for (ttl in AUTOTTL_RANGE) {
            for (split in TLS_SPLITS) {
                add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1"))
                add(listOf("--lua-desync=wssize:wsize=1:scale=6", payload, "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
}

/** 35-hostfake.sh — hostfakesplit варианты */
private fun hostfakeVariants(fooling: String): List<String> = buildList {
    for (disorder in HOSTFAKE_DISORDERS) {
        for (variant in HOSTFAKE_VARIANTS) {
            add("hostfakesplit:${disorder}${variant}$fooling:repeats=1")
        }
    }
}

private fun hostfakeHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"

    // TTL
    for (ttl in TTL_RANGE) {
        for (desync in hostfakeVariants("ip_ttl=$ttl")) {
            add(listOf(payload, "--lua-desync=$desync"))
            add(listOf(payload, "--lua-desync=$desync",
                "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }
    // foolings
    for (fooling in FOOLINGS_TCP) {
        for (desync in hostfakeVariants(fooling)) {
            add(listOf(payload, "--lua-desync=$desync"))
            if (fooling.contains("tcp_md5")) {
                add(listOf(payload, "--lua-desync=$desync",
                    "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            }
        }
    }
    // autottl
    for (ttl in AUTOTTL_RANGE) {
        for (desync in hostfakeVariants("ip_autottl=-$ttl,3-20")) {
            add(listOf(payload, "--lua-desync=$desync"))
            add(listOf(payload, "--lua-desync=$desync",
                "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }
}

private fun hostfakeTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"

    for (ttl in TTL_RANGE) {
        for (desync in hostfakeVariants("ip_ttl=$ttl")) {
            add(listOf(payload, "--lua-desync=$desync"))
            add(listOf(payload, "--lua-desync=$desync",
                "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }
    for (fooling in FOOLINGS_TCP) {
        for (desync in hostfakeVariants(fooling)) {
            add(listOf(payload, "--lua-desync=$desync"))
            if (fooling.contains("tcp_md5")) {
                add(listOf(payload, "--lua-desync=$desync",
                    "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            }
        }
    }
    for (ttl in AUTOTTL_RANGE) {
        for (desync in hostfakeVariants("ip_autottl=-$ttl,3-20")) {
            add(listOf(payload, "--lua-desync=$desync"))
            add(listOf(payload, "--lua-desync=$desync",
                "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
        }
    }
}

private fun hostfakeTls12(): List<List<String>> = buildList {
    addAll(hostfakeTls())
    // wssize варианты
    for (s in hostfakeTls()) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6") + s)
    }
}

/** 50-fake-multi.sh — fake + multisplit/multidisorder комбинации */
private fun fakeMultiHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"

    for (splitf in SPLIT_METHODS) {
        // TTL
        for (ttl in TTL_RANGE) {
            for (split in HTTP_SPLITS) {
                for (ff in FAKE_BLOBS_HTTP) {
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_ttl=$ttl:repeats=1",
                        "--lua-desync=$splitf:pos=$split"))
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_ttl=$ttl:repeats=1",
                        "--lua-desync=$splitf:pos=$split",
                        "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
        // foolings
        for (fooling in FOOLINGS_TCP) {
            for (split in HTTP_SPLITS) {
                for (ff in FAKE_BLOBS_HTTP) {
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:$fooling:repeats=1",
                        "--lua-desync=$splitf:pos=$split"))
                    if (fooling.contains("tcp_md5")) {
                        add(listOf(payload, "--lua-desync=fake:blob=$ff:$fooling:repeats=1",
                            "--lua-desync=$splitf:pos=$split",
                            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                    }
                }
            }
        }
        // autottl
        for (ttl in AUTOTTL_RANGE) {
            for (split in HTTP_SPLITS) {
                for (ff in FAKE_BLOBS_HTTP) {
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_autottl=-$ttl,3-20:repeats=1",
                        "--lua-desync=$splitf:pos=$split"))
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_autottl=-$ttl,3-20:repeats=1",
                        "--lua-desync=$splitf:pos=$split",
                        "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
    }
}

private fun fakeMultiTlsVary(fooling: String, splitf: String, split: String, fake: String): List<List<String>> {
    val payload = "--payload=tls_client_hello"
    return buildList {
        add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1", "--lua-desync=$splitf:pos=$split"))
        add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1", "--lua-desync=$splitf:pos=$split"))
        add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1",
            "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid:repeats=1", "--lua-desync=$splitf:pos=$split"))
        add(listOf(payload, "--lua-desync=multisplit:blob=$fake:$fooling:pos=2:nodrop:repeats=1", "--lua-desync=$splitf:pos=$split"))
        add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid,padencap:repeats=1", "--lua-desync=$splitf:pos=$split"))
        // SYN MD5
        if (fooling.contains("tcp_md5")) {
            add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1", "--lua-desync=$splitf:pos=$split",
                "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1", "--lua-desync=$splitf:pos=$split",
                "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            add(listOf(payload, "--lua-desync=fake:blob=0x00000000:$fooling:repeats=1",
                "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid:repeats=1", "--lua-desync=$splitf:pos=$split",
                "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            add(listOf(payload, "--lua-desync=multisplit:blob=$fake:$fooling:pos=2:nodrop:repeats=1", "--lua-desync=$splitf:pos=$split",
                "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
            add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:tls_mod=rnd,dupsid,padencap:repeats=1", "--lua-desync=$splitf:pos=$split",
                "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
        }
    }
}

private fun fakeMultiTls(): List<List<String>> = buildList {
    val fake = "fake_default_tls"

    for (splitf in SPLIT_METHODS) {
        // TTL
        for (ttl in TTL_RANGE) {
            for (split in TLS_SPLITS) {
                addAll(fakeMultiTlsVary("ip_ttl=$ttl", splitf, split, fake))
                // pktmod лимитер
                for (s in fakeMultiTlsVary("ip_ttl=$ttl", splitf, split, fake)) {
                    add(s + listOf("--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
        // foolings
        for (fooling in FOOLINGS_TCP) {
            for (split in TLS_SPLITS) {
                addAll(fakeMultiTlsVary(fooling, splitf, split, fake))
            }
        }
        // autottl
        for (ttl in AUTOTTL_RANGE) {
            for (split in TLS_SPLITS) {
                addAll(fakeMultiTlsVary("ip_autottl=-$ttl,3-20", splitf, split, fake))
                for (s in fakeMultiTlsVary("ip_autottl=-$ttl,3-20", splitf, split, fake)) {
                    add(s + listOf("--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
    }
}

private fun fakeMultiTls12(): List<List<String>> = buildList {
    addAll(fakeMultiTls())
    // wssize варианты
    for (s in fakeMultiTls()) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6") + s)
    }
}

/** 55-fake-faked.sh — fake + fakedsplit/fakeddisorder комбинации */
private fun fakeFakedHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    val splitfs = listOf("fakedsplit", "fakeddisorder")

    for (splitf in splitfs) {
        // TTL
        for (ttl in TTL_RANGE) {
            for (split in HTTP_SPLITS) {
                for (ff in FAKE_BLOBS_HTTP) {
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_ttl=$ttl:repeats=1",
                        "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1"))
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_ttl=$ttl:repeats=1",
                        "--lua-desync=$splitf:pos=$split:ip_ttl=$ttl:repeats=1",
                        "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
        // foolings
        for (fooling in FOOLINGS_TCP) {
            for (split in HTTP_SPLITS) {
                for (ff in FAKE_BLOBS_HTTP) {
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:$fooling:repeats=1",
                        "--lua-desync=$splitf:pos=$split:$fooling:repeats=1"))
                    if (fooling.contains("tcp_md5")) {
                        add(listOf(payload, "--lua-desync=fake:blob=$ff:$fooling:repeats=1",
                            "--lua-desync=$splitf:pos=$split:$fooling:repeats=1",
                            "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                    }
                }
            }
        }
        // autottl
        for (ttl in AUTOTTL_RANGE) {
            for (split in HTTP_SPLITS) {
                for (ff in FAKE_BLOBS_HTTP) {
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_autottl=-$ttl,3-20:repeats=1",
                        "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1"))
                    add(listOf(payload, "--lua-desync=fake:blob=$ff:ip_autottl=-$ttl,3-20:repeats=1",
                        "--lua-desync=$splitf:pos=$split:ip_autottl=-$ttl,3-20:repeats=1",
                        "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
    }
}

private fun fakeFakedTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    val fake = "fake_default_tls"
    val splitfs = listOf("fakedsplit", "fakeddisorder")

    for (splitf in splitfs) {
        // TTL
        for (ttl in TTL_RANGE) {
            for (split in TLS_SPLITS) {
                // 5 вариаций fake
                for (s in fakeMultiTlsVary("ip_ttl=$ttl", splitf, split, fake)) {
                    // Заменяем splitf часть на faked-версию с ttl
                    add(replaceLastSplitArg(s, splitf, split, "ip_ttl=$ttl:repeats=1"))
                }
                // pktmod
                for (s in fakeMultiTlsVary("ip_ttl=$ttl", splitf, split, fake)) {
                    add(replaceLastSplitArg(s, splitf, split, "ip_ttl=$ttl:repeats=1") +
                        listOf("--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
        // foolings
        for (fooling in FOOLINGS_TCP) {
            for (split in TLS_SPLITS) {
                for (s in fakeMultiTlsVary(fooling, splitf, split, fake)) {
                    add(replaceLastSplitArg(s, splitf, split, "$fooling:repeats=1"))
                }
            }
        }
        // autottl
        for (ttl in AUTOTTL_RANGE) {
            for (split in TLS_SPLITS) {
                for (s in fakeMultiTlsVary("ip_autottl=-$ttl,3-20", splitf, split, fake)) {
                    add(replaceLastSplitArg(s, splitf, split, "ip_autottl=-$ttl,3-20:repeats=1"))
                }
                for (s in fakeMultiTlsVary("ip_autottl=-$ttl,3-20", splitf, split, fake)) {
                    add(replaceLastSplitArg(s, splitf, split, "ip_autottl=-$ttl,3-20:repeats=1") +
                        listOf("--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
                }
            }
        }
    }
}

/** Заменяет аргумент --lua-desync=$splitf:pos=$split на версию с дополнительными параметрами */
private fun replaceLastSplitArg(
    args: List<String>,
    splitf: String,
    split: String,
    extra: String,
): List<String> {
    val target = "--lua-desync=$splitf:pos=$split"
    val replacement = "--lua-desync=$splitf:pos=$split:$extra"
    return args.map { if (it == target) replacement else it }
}

private fun fakeFakedTls12(): List<List<String>> = buildList {
    addAll(fakeFakedTls())
    for (s in fakeFakedTls()) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6") + s)
    }
}

/** 60-fake-hostfake.sh — fake + hostfakesplit комбинации */
private fun fakeHostfakeHttp(): List<List<String>> = buildList {
    val payload = "--payload=http_req"
    val fake = "fake_default_http"

    // TTL
    for (ttl in TTL_RANGE) {
        for (disorder in HOSTFAKE_DISORDERS) {
            for (variant in HOSTFAKE_VARIANTS) {
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_ttl=$ttl:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_ttl=$ttl:repeats=1"))
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_ttl=$ttl:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_ttl=$ttl:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
    // foolings
    for (fooling in FOOLINGS_TCP) {
        for (disorder in HOSTFAKE_DISORDERS) {
            for (variant in HOSTFAKE_VARIANTS) {
                add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}$fooling:repeats=1"))
                if (fooling.contains("tcp_md5")) {
                    add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1",
                        "--lua-desync=hostfakesplit:${disorder}${variant}$fooling:repeats=1",
                        "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                }
            }
        }
    }
    // autottl
    for (ttl in AUTOTTL_RANGE) {
        for (disorder in HOSTFAKE_DISORDERS) {
            for (variant in HOSTFAKE_VARIANTS) {
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_autottl=-$ttl,3-20:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_autottl=-$ttl,3-20:repeats=1"))
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_autottl=-$ttl,3-20:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_autottl=-$ttl,3-20:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
}

private fun fakeHostfakeTls(): List<List<String>> = buildList {
    val payload = "--payload=tls_client_hello"
    val fake = "fake_default_tls"

    // TTL
    for (ttl in TTL_RANGE) {
        for (disorder in HOSTFAKE_DISORDERS) {
            for (variant in HOSTFAKE_VARIANTS) {
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_ttl=$ttl:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_ttl=$ttl:repeats=1"))
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_ttl=$ttl:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_ttl=$ttl:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
    // foolings
    for (fooling in FOOLINGS_TCP) {
        for (disorder in HOSTFAKE_DISORDERS) {
            for (variant in HOSTFAKE_VARIANTS) {
                add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}$fooling:repeats=1"))
                if (fooling.contains("tcp_md5")) {
                    add(listOf(payload, "--lua-desync=fake:blob=$fake:$fooling:repeats=1",
                        "--lua-desync=hostfakesplit:${disorder}${variant}$fooling:repeats=1",
                        "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"))
                }
            }
        }
    }
    // autottl
    for (ttl in AUTOTTL_RANGE) {
        for (disorder in HOSTFAKE_DISORDERS) {
            for (variant in HOSTFAKE_VARIANTS) {
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_autottl=-$ttl,3-20:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_autottl=-$ttl,3-20:repeats=1"))
                add(listOf(payload, "--lua-desync=fake:blob=$fake:ip_autottl=-$ttl,3-20:repeats=1",
                    "--lua-desync=hostfakesplit:${disorder}${variant}ip_autottl=-$ttl,3-20:repeats=1",
                    "--payload=empty", "--out-range=s1<d1", "--lua-desync=pktmod:ip_ttl=1"))
            }
        }
    }
}

private fun fakeHostfakeTls12(): List<List<String>> = buildList {
    addAll(fakeHostfakeTls())
    for (s in fakeHostfakeTls()) {
        add(listOf("--lua-desync=wssize:wsize=1:scale=6") + s)
    }
}

// --- Точка входа ---

fun generateStrategies(protocol: Protocol): List<List<String>> = when (protocol) {
    Protocol.HTTP -> buildList {
        addAll(httpBasic())
        addAll(miscHttp())
        addAll(oob())
        addAll(multiHttp())
        addAll(seqovlHttp())
        addAll(syndataHttp())
        addAll(fakeHttp())
        addAll(fakedHttp())
        addAll(hostfakeHttp())
        addAll(fakeMultiHttp())
        addAll(fakeFakedHttp())
        addAll(fakeHostfakeHttp())
    }
    Protocol.HTTPS_TLS12 -> buildList {
        addAll(miscTls())
        addAll(oob())
        addAll(multiTls12())
        addAll(seqovlTls12())
        addAll(syndataTls12())
        addAll(fakeTls12())
        addAll(fakedTls12())
        addAll(hostfakeTls12())
        addAll(fakeMultiTls12())
        addAll(fakeFakedTls12())
        addAll(fakeHostfakeTls12())
    }
    Protocol.HTTPS_TLS13 -> buildList {
        addAll(miscTls())
        addAll(oob())
        addAll(multiTls())
        addAll(seqovlTls())
        addAll(syndataTls())
        addAll(fakeTls())
        addAll(fakedTls())
        addAll(hostfakeTls())
        addAll(fakeMultiTls())
        addAll(fakeFakedTls())
        addAll(fakeHostfakeTls())
    }
}
