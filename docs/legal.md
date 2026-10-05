# Recording calls and the law

**This is not legal advice.** It is a working note for building the app, written
by reading primary sources and secondary commentary. Nothing here has been
reviewed by a lawyer in any jurisdiction. Before Ravam ships anything to the
Gulf, buy an hour of UAE-qualified time — see the four questions in `PLAN.md`.

## The one idea that matters

Countries split into two camps, and the split is not about *technology*, it is
about *who has to agree*.

| | Rule | Examples |
|---|---|---|
| **One-party-ish** | A participant may generally record | India, most of the US, UK |
| **All-party** | Everyone on the call must agree, first | UAE and the Gulf, most of Europe, several US states |

Being a participant does **not** exempt you in an all-party country. That is the
most common and most expensive misunderstanding in this area, and people have
been prosecuted on exactly that assumption.

The law is about the **conversation**, not the channel. A cellular call, a BOTIM
call, a WhatsApp call and two people talking in a room are treated the same.

## UAE — Article 44

Federal Decree-Law No. 34 of 2021, Article 44. Recording, transmitting or
disclosing a private conversation without consent:

- **Detention of not less than six months**, and/or
- **AED 150,000 – 500,000**

Confirmed against the UAE legislation portal. Two things that matter for design:

- **Intent is an element.** UAE courts weigh *mens rea*, and good faith is one
  of the few defences entertained. This is precisely why Ravam must never write
  down that the user was warned and proceeded anyway — see `PLAN.md` §6.
- **Amicable settlement is available** for Article 44, which changes the
  practical risk picture but not the legal one.

A unilateral spoken announcement is **not** established to constitute the other
party's consent. Build the announcement because it is decent and genuinely
useful; never tell a user in Dubai that it makes them lawful.

## India — more nuanced than "one-party consent"

"One-party consent" is American framing and India has no such statute.

*Vibhor Garg v. Neha* (2025 INSC 829, 14 July 2025) held a spouse's secretly
recorded call **admissible in evidence** in matrimonial proceedings. That is
admissibility, not legality, and a ruling about spouses in a divorce is not a
general licence. Legality separately engages the Indian Telegraph Act s.25, the
IT Act, and the Article 21 privacy right after *Puttaswamy*. The Telangana High
Court took a narrower view in 2026.

Practically: a participant recording their own call in India is on
well-trodden ground. It is not the blanket permission the phrase implies.

## Saudi Arabia

Ship as **all-party**. Penalties reach one year and SAR 500,000. Defaulting it
the other way would auto-record Gulf users into an offence.

## Cross-border calls

There is no treaty deciding which law wins. **Both apply at once.**

The reference case for this project: a user physically in India, on an Indian
SIM, calling a `+971` number. India's rules cover the act where it happens. The
UAE's cybercrime law reaches offences committed *outside* the UAE when the
victim is inside it. So the recording can be lawful in India and chargeable in
the UAE simultaneously.

Enforcement concentrates at the border — complaints filed on the UAE eCrime
portal, cases opened in absentia, arrests on arrival. For a user base that flies
to the Gulf constantly, that is not theoretical.

**Design consequence:** apply the **strictest** rule in the union of (network
country of the SIM carrying the call, region of the number being dialled, SIM
home countries). Any all-party member → ask first. A dual-SIM phone with a UAE
and an Indian SIM breaks any single-signal approach, which is exactly the
reference device.

## What Ravam does about it

Summarised from `PLAN.md` §6:

1. Never persist that a user was warned and proceeded. Nowhere. Not the
   filename, not a sidecar, not preferences.
2. Keep the warning; drop the acceptance. Both buttons pick a recording mode.
3. Never block the app. Jurisdiction sets a default, never access.
4. Help the user get the one record that actually defends them: the other
   person's spoken agreement, captured inside the recording itself.
5. Positive-only ledger. No negative rows, ever.
6. Never request location permission to work out where the user is.
