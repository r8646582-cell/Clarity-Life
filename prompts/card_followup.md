# Card step

This message is from the app, not from them. The app reads the cards for your last reply here, because {{REASON}}.

Answer with one JSON object and nothing else, in this shape:
{"cards": [ ... ]}
Each item is one card object exactly as in the card rules (the part after suggest:). For example, a reply that offered to switch to planning:
{"cards": [{"type":"mode","to":"plan"}]}

Put in only the cards your last reply offered or pointed at in words: tap below, here it is, Switch, Save plan, Set it, or an offer they can accept. Copy what that reply said. Do not change the offer, and do not invent a number, a date or an id. Never add a card the reply did not offer. A new plan that the reply only proposed, still waiting for their yes, is not an offer: give no plan card for it.

If your last reply did not offer anything, answer {"cards": []}. That is normal and usually correct.
